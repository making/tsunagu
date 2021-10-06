package am.ik.tsunagu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import io.rsocket.metadata.CompositeMetadata;
import io.rsocket.metadata.CompositeMetadata.Entry;
import io.rsocket.util.DefaultPayload;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;
import reactor.util.retry.Retry;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
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

	private final ConfigurableApplicationContext context;

	public TsunaguConnector(Builder requesterBuilder, WebClient.Builder webClientBuilder, TsunaguProps props, ConfigurableApplicationContext context) throws SSLException {
		this.requester = requesterBuilder
				.setupData(Map.of("token", props.getToken()))
				.rsocketConnector(connector -> connector
						.reconnect(Retry.fixedDelay(Long.MAX_VALUE, Duration.ofSeconds(1))
								.doBeforeRetry(s -> log.info("Reconnecting to {}. ({})", props.getRemote(), s)))
						.acceptor((setup, sendingSocket) -> Mono.just(TsunaguConnector.this)))
				.websocket(props.getRemote());
		final SslContext sslContext = SslContextBuilder.forClient()
				.trustManager(InsecureTrustManagerFactory.INSTANCE).build();
		final HttpClient httpClient = HttpClient.create().secure(ssl -> ssl.sslContext(sslContext));
		this.webClient = webClientBuilder
				.clientConnector(new ReactorClientHttpConnector(httpClient))
				.build();
		this.webSocketClient = new ReactorNettyWebSocketClient(httpClient,
				() -> WebsocketClientSpec.builder()
						.maxFramePayloadLength(props.getWebSocketMaxFramePayloadLength()));
		this.props = props;
		this.objectMapper = Jackson2ObjectMapperBuilder.cbor().build();
		this.context = context;
	}

	@Override
	public Mono<Void> fireAndForget(Payload payload) {
		log.error(payload.getDataUtf8());
		return Mono.<Void>empty()
				.doFinally(__ -> context.close());
	}

	HttpRequestMetadata getHttpRequestMetadata(Payload payload) throws IOException {
		final CompositeMetadata entries = new CompositeMetadata(payload.metadata(), true);
		final Map<String, ByteBuf> metadataMap = entries.stream().collect(Collectors.toUnmodifiableMap(Entry::getMimeType, Entry::getContent));
		final byte[] httpRequestMetadataBytes = ByteBufUtil.getBytes(metadataMap.get("application/cbor"));
		return this.objectMapper.readValue(httpRequestMetadataBytes, HttpRequestMetadata.class);
	}

	@Override
	public Flux<Payload> requestStream(Payload payload) {
		try {
			final HttpRequestMetadata httpRequestMetadata = this.getHttpRequestMetadata(payload);
			final URI uri = UriComponentsBuilder.fromUri(httpRequestMetadata.getUri())
					.uri(this.props.getUpstream())
					.build(false)
					.toUri();
			return this.webClient.method(httpRequestMetadata.getMethod())
					.uri(uri)
					.headers(this.copyHeaders(httpRequestMetadata))
					.exchangeToFlux(this.handleResponse(httpRequestMetadata));
		}
		catch (IOException e) {
			return Flux.<Payload>error(e).log("requestStream");
		}
	}

	@Override
	public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
		return Flux.from(payloads)
				.switchOnFirst((signal, flux) -> {
					if (signal.hasValue()) {
						try {
							final HttpRequestMetadata httpRequestMetadata = this.getHttpRequestMetadata(signal.get());
							final URI uri = UriComponentsBuilder.fromUri(httpRequestMetadata.getUri())
									.uri(this.props.getUpstream())
									.build(false)
									.toUri();
							if (httpRequestMetadata.isWebSocketRequest()) {
								final HttpHeaders httpHeaders = new HttpHeaders();
								this.copyHeaders(httpRequestMetadata).accept(httpHeaders);
								if (log.isInfoEnabled()) {
									log.info("{}\t101 {} {}", httpRequestMetadata.getMethod(), httpRequestMetadata.getUri(), httpRequestMetadata.getHeaders().getFirst(HttpHeaders.USER_AGENT));
								}
								return Flux.create(sink -> sink.onDispose(this.webSocketClient.execute(uri, httpHeaders,
										session -> session
												.send(flux.map(payload -> session.binaryMessage(factory -> factory.wrap(payload.getData())))).and(session.receive().doOnNext(message -> {
															final ByteBuffer payload = message.getPayload().asByteBuffer();
															// the first 1 byte of the data is the message type
															final ByteBuffer data = ByteBuffer.allocate(1 + payload.remaining())
																	.put((byte) message.getType().ordinal())
																	.put(payload)
																	.flip();
															sink.next(DefaultPayload.create(data));
														})
														.doOnError(sink::error)
														.doOnComplete(sink::complete))).subscribe()));
							}
							return this.webClient.method(httpRequestMetadata.getMethod())
									.uri(uri)
									.body(flux.map(Payload::data), ByteBuf.class)
									.headers(this.copyHeaders(httpRequestMetadata))
									.exchangeToFlux(this.handleResponse(httpRequestMetadata));
						}
						catch (IOException e) {
							return Flux.<Payload>error(e).log("requestChannel");
						}
					}
					else {
						return flux.log("wth");
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

	Function<ClientResponse, Flux<Payload>> handleResponse(HttpRequestMetadata httpRequestMetadata) {
		return response -> {
			try {
				final HttpResponseMetadata httpResponseMetadata = new HttpResponseMetadata(response.statusCode(), response.headers().asHttpHeaders());
				final byte[] httpResponseMetadataBytes = this.objectMapper.writeValueAsBytes(httpResponseMetadata);
				return Mono.just(DefaultPayload.create(httpResponseMetadataBytes)) // send response header first
						.concatWith(response.bodyToFlux(ByteBuf.class) // then send response body
								.doFinally(__ -> {
									if (log.isInfoEnabled()) {
										log.info("{}\t{} {} {}", httpRequestMetadata.getMethod(), httpResponseMetadata.getStatus().value(), httpRequestMetadata.getUri(), httpRequestMetadata.getHeaders().getFirst(HttpHeaders.USER_AGENT));
									}
								})
								.map(DefaultPayload::create)
								.switchIfEmpty(Mono.fromCallable(() -> DefaultPayload.create(Unpooled.EMPTY_BUFFER))));
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
				.doOnRequest(__ -> log.info("Connecting to {}", this.props.getRemote()))
				.retryWhen(Retry.fixedDelay(Long.MAX_VALUE, Duration.ofSeconds(1))
						.doBeforeRetry(s -> log.info("Reconnecting to {}. {}", this.props.getRemote(), s)))
				.doOnSuccess(s -> log.info("Connected ({})", s))
				.doOnError(e -> log.error("Failed to connect ({})", e.getMessage()))
				.subscribe();
	}

}
