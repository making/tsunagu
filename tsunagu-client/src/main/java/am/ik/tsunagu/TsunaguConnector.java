package am.ik.tsunagu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.net.ssl.SSLException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import io.rsocket.transport.ClientTransport;
import io.rsocket.transport.netty.client.WebsocketClientTransport;
import io.rsocket.util.DefaultPayload;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
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

	private final ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

	private ScheduledFuture<?> verificationScheduledFuture = null;

	public TsunaguConnector(Builder requesterBuilder, WebClient.Builder webClientBuilder, TsunaguProps props, ConfigurableApplicationContext context) throws SSLException {
		final SslContext sslContext = SslContextBuilder.forClient()
				.trustManager(InsecureTrustManagerFactory.INSTANCE).build();
		this.requester = requesterBuilder
				.setupData(Map.of("token", props.getToken()))
				.rsocketConnector(connector -> connector
						.reconnect(Retry.fixedDelay(Long.MAX_VALUE, Duration.ofSeconds(1))
								.doBeforeRetry(s -> log.info("Reconnecting to " + props.getRemote() + ". (" + s + ")", s.failure())))
						.acceptor((setup, sendingSocket) -> Mono.just(TsunaguConnector.this)))
				.transport(buildClientTransport(props, sslContext));
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

	static ClientTransport buildClientTransport(TsunaguProps props, SslContext sslContext) {
		final URI uri = props.getRemote();
		boolean isSecure = uri.getScheme().equals("wss") || uri.getScheme().equals("https");
		HttpClient client =
				(isSecure ? HttpClient.create().secure(ssl -> ssl.sslContext(sslContext)) : HttpClient.create())
						.host(uri.getHost())
						.port(uri.getPort() == -1 ? (isSecure ? 443 : 80) : uri.getPort());
		return WebsocketClientTransport.create(client, uri.getPath())
				.webSocketSpec(spec -> spec.maxFramePayloadLength(props.getWebSocketMaxFramePayloadLength()));
	}

	@Override
	public Mono<Void> fireAndForget(Payload payload) {
		final String data = payload.getDataUtf8();
		final Consumer<SignalType> closer = __ -> context.close();
		if (data.startsWith("{") && data.endsWith("}")) {
			try {
				final JsonNode response = new ObjectMapper().readValue(data, JsonNode.class);
				if (response.has("type")) {
					final String type = response.get("type").asText();
					if ("connected".equals(type) && response.has("requesterId")) {
						if (this.verificationScheduledFuture != null) {
							log.info("cancel existing verification");
							final boolean canceled = this.verificationScheduledFuture.cancel(true);
							if (!canceled) {
								log.info("failed to cancel...");
							}
						}
						final String requesterId = response.get("requesterId").asText();
						final TsunaguConnectionVerifier verifier = new TsunaguConnectionVerifier(requesterId, this.context, this.props);
						log.info("start verification for the requester({})", requesterId);
						this.verificationScheduledFuture = this.scheduledExecutor.scheduleAtFixedRate(verifier::verifyConnection, 0, 1, TimeUnit.MINUTES);
						return Mono.empty();
					}
				}
			}
			catch (JsonProcessingException e) {
				log.error("can't parse json", e);
				return Mono.<Void>error(e).doFinally(closer);
			}
		}
		log.error(data);
		return Mono.<Void>empty().doFinally(closer);
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
					.build(true)
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
									.build(true)
									.toUri();
							if (httpRequestMetadata.isWebSocketRequest()) {
								final HttpHeaders httpHeaders = new HttpHeaders();
								this.copyHeaders(httpRequestMetadata).accept(httpHeaders);
								if (log.isInfoEnabled()) {
									log.info("{}\t{}\t101 {} {}", httpHeaders.getFirst("X-Real-IP"), httpRequestMetadata.getMethod(), httpRequestMetadata.getUri(), httpRequestMetadata.getHeaders().getFirst(HttpHeaders.USER_AGENT));
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
			if (this.props.isPreserveHost()) {
				final Map<String, String> hostMap = this.props.getHostMap();
				final String originalHost = headers.getFirst(HttpHeaders.HOST);
				final String mappedHost = hostMap.get(originalHost);
				if (mappedHost != null) {
					log.debug("Mapping host: {} => {}", originalHost, mappedHost);
					headers.replace(HttpHeaders.HOST, List.of(mappedHost));
				}
			}
			else {
				headers.remove(HttpHeaders.HOST);
			}
			final Map<String, String> pathToHostMap = this.props.getPathToHostMap();
			if (!pathToHostMap.isEmpty()) {
				final String path = removeLeadingSlash(httpRequestMetadata.getUri().getPath());
				pathToHostMap.forEach((pathPrefix, host) -> {
					if (path.startsWith(pathPrefix)) {
						log.debug("Mapping pathToHost: /{} => {}", path, host);
						headers.replace(HttpHeaders.HOST, List.of(host));
					}
				});
			}
		};
	}

	static String removeLeadingSlash(String path) {
		return path.startsWith("/") ? path.substring(1) : path;
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
										final HttpHeaders httpHeaders = httpRequestMetadata.getHeaders();
										log.info("{}\t{}\t{} {} {}", httpHeaders.getFirst("X-Real-IP"), httpRequestMetadata.getMethod(), httpResponseMetadata.getStatus().value(), httpRequestMetadata.getUri(), httpHeaders.getFirst(HttpHeaders.USER_AGENT));
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
						.doBeforeRetry(s -> log.info("Reconnecting to " + props.getRemote() + ". (" + s + ")", s.failure())))
				.doOnSuccess(s -> log.info("Connected ({})", s))
				.doOnError(e -> log.error("Failed to connect ({})", e.getMessage()))
				.subscribe();
	}

}
