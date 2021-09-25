package am.ik.tsunagu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.net.ssl.SSLException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.util.DefaultPayload;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import org.springframework.boot.CommandLineRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketRequester.Builder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class TsunaguConnector implements RSocket, CommandLineRunner {
	private final RSocketRequester requester;

	private final WebClient webClient;

	private final WebSocketClient webSocketClient;

	private final Logger log = LoggerFactory.getLogger(TsunaguConnector.class);

	private final ObjectMapper objectMapper;

	private final TsunaguProps props;

	public TsunaguConnector(Builder requesterBuilder, WebClient.Builder webClientBuilder, TsunaguProps props, ObjectMapper objectMapper) throws SSLException {
		this.objectMapper = objectMapper;
		this.requester = requesterBuilder
				.rsocketConnector(connector -> connector
						.reconnect(Retry.fixedDelay(Long.MAX_VALUE, Duration.ofSeconds(1))
								.doBeforeRetry(s -> log.info("Reconnect: {}", s)))
						.acceptor((setup, sendingSocket) -> Mono.just(TsunaguConnector.this)))
				.websocket(props.getRemote());
		final SslContext sslContext = SslContextBuilder.forClient()
				.trustManager(InsecureTrustManagerFactory.INSTANCE).build();
		final HttpClient httpClient = HttpClient.create().secure(ssl -> ssl.sslContext(sslContext));
		this.webClient = webClientBuilder
				.clientConnector(new ReactorClientHttpConnector(httpClient))
				.build();
		this.webSocketClient = new ReactorNettyWebSocketClient(httpClient);
		this.props = props;
	}

	@Override
	public Flux<Payload> requestStream(Payload payload) {
		try {
			final byte[] httpRequestMetadataBytes = ByteBufUtil.getBytes(payload.metadata());
			final HttpRequestMetadata httpRequestMetadata = this.objectMapper.readValue(httpRequestMetadataBytes, HttpRequestMetadata.class);
			final URI uri = UriComponentsBuilder.fromUri(httpRequestMetadata.getUri())
					.uri(this.props.getUpstream())
					.build()
					.toUri();
			return this.webClient.method(httpRequestMetadata.getMethod())
					.uri(uri)
					.headers(this.copyHeaders(httpRequestMetadata))
					.exchangeToFlux(this.handleResponse());
		}
		catch (IOException e) {
			return Flux.error(e);
		}
	}

	@Override
	public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
		return Flux.from(payloads)
				.switchOnFirst((signal, flux) -> {
					try {
						final byte[] httpRequestMetadataBytes = ByteBufUtil.getBytes(signal.get().metadata());
						final HttpRequestMetadata httpRequestMetadata = this.objectMapper.readValue(httpRequestMetadataBytes, HttpRequestMetadata.class);
						final URI uri = UriComponentsBuilder.fromUri(httpRequestMetadata.getUri())
								.uri(this.props.getUpstream())
								.build()
								.toUri();
						if (httpRequestMetadata.isWebSocketRequest()) {
							final HttpHeaders httpHeaders = new HttpHeaders();
							this.copyHeaders(httpRequestMetadata).accept(httpHeaders);
							return Flux.create(sink ->
									this.webSocketClient.execute(uri, httpHeaders,
													session -> session
															.send(flux.map(payload -> session.binaryMessage(factory -> factory.wrap(payload.getData()))))
															.and(session.receive()
																	.doOnNext(message -> {
																		final ByteBuffer data = message.getPayload().asByteBuffer();
																		final ByteBuffer metadata = ByteBuffer.allocate(1).put((byte) message.getType().ordinal()).flip();
																		sink.next(DefaultPayload.create(data, metadata));
																	})
																	.doOnError(sink::error)
																	.doOnComplete(sink::complete)))
											.subscribe());
						}
						return this.webClient.method(httpRequestMetadata.getMethod())
								.uri(uri)
								.body(flux.map(Payload::data), ByteBuf.class)
								.headers(this.copyHeaders(httpRequestMetadata))
								.exchangeToFlux(this.handleResponse());
					}
					catch (IOException e) {
						return Flux.error(e);
					}
				});
	}

	Consumer<HttpHeaders> copyHeaders(HttpRequestMetadata httpRequestMetadata) {
		return headers -> {
			headers.addAll(httpRequestMetadata.getHeaders());
			if (!this.props.isPreserveHost()) {
				headers.remove(HttpHeaders.HOST);
			}
		};
	}

	Function<ClientResponse, Flux<Payload>> handleResponse() {
		return response -> {
			try {
				final HttpResponseMetadata httpResponseMetadata = new HttpResponseMetadata(response.statusCode(), response.headers().asHttpHeaders());
				final byte[] httpResponseMetadataBytes = this.objectMapper.writeValueAsBytes(httpResponseMetadata);
				final AtomicBoolean headerSent = new AtomicBoolean(false);
				return response.bodyToFlux(ByteBuf.class)
						.map(body -> DefaultPayload.create(body, headerSent.compareAndSet(false, true) ? Unpooled.copiedBuffer(httpResponseMetadataBytes) : Unpooled.EMPTY_BUFFER))
						.switchIfEmpty(Mono.fromCallable(() -> DefaultPayload.create(new byte[] {}, httpResponseMetadataBytes)));
			}
			catch (JsonProcessingException e) {
				throw new UncheckedIOException(e);
			}
		};
	}

	@Override
	public void run(String... args) throws Exception {
		this.connect();
		Hooks.onErrorDropped(e -> {
			log.warn("Connection closed");
			this.connect();
		});
	}

	void connect() {
		this.requester
				.route("version_check")
				.retrieveMono(String.class)
				.log("versionCheck")
				.retryWhen(Retry.fixedDelay(Long.MAX_VALUE, Duration.ofSeconds(1))
						.doBeforeRetry(s -> log.info("VersionCheck Retry: {}", s)))
				.subscribe();
	}

}
