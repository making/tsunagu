package am.ik.tsunagu;

import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.util.DefaultPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import org.springframework.boot.CommandLineRunner;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketRequester.Builder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class TsunaguConnector implements RSocket, CommandLineRunner {
	private final RSocketRequester requester;

	private final WebClient webClient;

	private final Logger log = LoggerFactory.getLogger(TsunaguConnector.class);

	private final ObjectMapper objectMapper;

	private final TsunaguProps props;

	public TsunaguConnector(Builder requesterBuilder, WebClient.Builder webClientBuilder, TsunaguProps props, ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
		this.requester = requesterBuilder
				.rsocketConnector(connector -> connector
						.reconnect(Retry.fixedDelay(Long.MAX_VALUE, Duration.ofSeconds(1))
								.doBeforeRetry(s -> log.info("Reconnect: {}", s)))
						.acceptor((setup, sendingSocket) -> Mono.just(TsunaguConnector.this)))
				.websocket(props.getRemote());
		this.webClient = webClientBuilder.build();
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
					.headers(httpHeaders -> httpHeaders.addAll(httpRequestMetadata.getHeaders()))
					.exchangeToFlux(response -> {
						try {
							final HttpResponseMetadata httpResponseMetadata = new HttpResponseMetadata(response.statusCode(), response.headers().asHttpHeaders());
							final byte[] httpResponseMetadataBytes = this.objectMapper.writeValueAsBytes(httpResponseMetadata);
							final AtomicBoolean headerSent = new AtomicBoolean(false);
							return response.bodyToFlux(ByteBuf.class)
									.map(body -> DefaultPayload.create(body, headerSent.compareAndSet(false, true) ? Unpooled.copiedBuffer(httpResponseMetadataBytes) : Unpooled.EMPTY_BUFFER))
									.switchIfEmpty(Flux.just(DefaultPayload.create(new byte[] {}, httpResponseMetadataBytes)));
						}
						catch (JsonProcessingException e) {
							throw new UncheckedIOException(e);
						}
					});
		}
		catch (Throwable e) {
			return Flux.error(e);
		}
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
