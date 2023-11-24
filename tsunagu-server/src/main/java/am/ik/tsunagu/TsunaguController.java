package am.ik.tsunagu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.rsocket.core.RSocketClient;
import io.rsocket.util.DefaultPayload;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.annotation.ConnectMapping;
import org.springframework.util.Base64Utils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketMessage.Type;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class TsunaguController implements Function<ServerHttpRequest, WebSocketHandler> {
	private final Logger log = LoggerFactory.getLogger(TsunaguController.class);

	private final ConcurrentMap<UUID, RSocketRequester> requesters = new ConcurrentHashMap<>();

	private final ObjectMapper objectMapper;

	private final TsunaguProps props;

	public TsunaguController(TsunaguProps props) {
		this.objectMapper = Jackson2ObjectMapperBuilder.cbor().build();
		this.props = props;
	}

	private RSocketRequester getRequester() {
		final Collection<RSocketRequester> rSocketRequesters = requesters.values();
		if (rSocketRequesters.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No requester found.");
		}
		if (rSocketRequesters.size() == 1) {
			return rSocketRequesters.iterator().next();
		}
		final List<RSocketRequester> list = new ArrayList<>(rSocketRequesters);
		Collections.shuffle(list, ThreadLocalRandom.current());
		return list.get(0);
	}

	@GetMapping(path = "/.well-known/acme-challenge/{key}")
	public ResponseEntity<?> acmeChallenge(@PathVariable("key") String key) {
		final String value = this.props.getAcmeChallenge().get(key);
		if (value != null) {
			return ResponseEntity.ok(value);
		}
		else {
			return ResponseEntity.notFound().build();
		}
	}

	@GetMapping(path = "/.tsunagu/requesters")
	public Collection<UUID> requesters() {
		return this.requesters.keySet();
	}

	@RequestMapping(path = "**")
	public Mono<Void> proxy(ServerHttpRequest request, ServerHttpResponse response) throws Exception {
		this.checkAuthorization(request);
		final HttpHeaders httpHeaders = setForwardHeaders(request);
		final HttpRequestMetadata httpRequestMetadata = new HttpRequestMetadata(request.getMethod(), request.getURI(), httpHeaders);
		final Flux<DataBuffer> responseStream;
		if (httpRequestMetadata.hasBody()) {
			responseStream = this.getRequester()
					.route("_")
					.metadata(httpRequestMetadata, MediaType.APPLICATION_CBOR)
					.data(request.getBody(), DataBuffer.class)
					.retrieveFlux(DataBuffer.class);
		}
		else {
			responseStream = this.getRequester()
					.route("_")
					.metadata(httpRequestMetadata, MediaType.APPLICATION_CBOR)
					.retrieveFlux(DataBuffer.class);
		}
		return responseStream.switchOnFirst(this.handleResponse(httpRequestMetadata, response)).then();
	}

	BiFunction<Signal<? extends DataBuffer>, Flux<DataBuffer>, Publisher<? extends Void>> handleResponse(HttpRequestMetadata httpRequestMetadata, ServerHttpResponse response) {
		return (signal, flux) -> {
			if (signal.hasValue()) {
				final byte[] httpResponseMetadataBytes = signal.get().asByteBuffer().array();
				final Flux<DataBuffer> body = flux.skip(1);
				try {
					final HttpResponseMetadata httpResponseMetadata = this.objectMapper.readValue(httpResponseMetadataBytes, HttpResponseMetadata.class);
					response.setStatusCode(httpResponseMetadata.getStatus());
					final HttpHeaders responseHeaders = response.getHeaders();
					responseHeaders.addAll(httpResponseMetadata.getHeaders());
					// https://stackoverflow.com/a/61493578/5861829
					responseHeaders.remove(HttpHeaders.TRANSFER_ENCODING);
					return response.writeWith(body)
							.doFinally(__ -> {
								if (log.isInfoEnabled()) {
									final HttpHeaders httpHeaders = httpRequestMetadata.getHeaders();
									log.info("{}\t{}\t{} {} {}", httpHeaders.getFirst("X-Real-IP"), httpRequestMetadata.getMethod(), httpResponseMetadata.getStatus().value(), httpRequestMetadata.getUri(), httpHeaders.getFirst(HttpHeaders.USER_AGENT));
								}
							});
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			return flux.log("wth").then();
		};
	}

	@ConnectMapping
	public void connect(RSocketRequester requester, @org.springframework.messaging.handler.annotation.Payload Map<String, String> data) {
		final RSocketClient rsocketClient = requester.rsocketClient();
		final UUID requesterId = UUID.randomUUID();
		if (!Objects.equals(this.props.getToken(), data.get("token"))) {
			rsocketClient.fireAndForget(Mono.just(DefaultPayload.create("{\"type\":\"error\",\"message\":\"Token is wrong.\"}"))).subscribe();
			return;
		}
		requester.rsocket()
				.onClose()
				.doFirst(() -> {
					requesters.put(requesterId, requester);
					log.info("Client: Connected ({}) clients={}", requesterId, requesters.keySet());
					rsocketClient.fireAndForget(Mono.just(DefaultPayload.create("{\"type\":\"connected\",\"requesterId\":\"" + requesterId + "\"}"))).subscribe();
				})
				.doOnError(error -> {
					log.warn("Client: Error (" + requester + ")", error);
				})
				.doFinally(consumer -> {
					requesters.remove(requesterId);
					log.info("Client: Disconnected ({}) clients={}", requesterId, requesters.keySet());
				})
				.subscribe();
	}

	@MessageMapping("version_check")
	public String versionCheck() {
		return "OK";
	}

	@Override
	public WebSocketHandler apply(ServerHttpRequest request) {
		return (session) -> {
			final HttpHeaders httpHeaders = setForwardHeaders(request);
			final HttpRequestMetadata httpRequestMetadata = new HttpRequestMetadata(request.getMethod(), request.getURI(), httpHeaders);
			final Flux<DataBuffer> responseStream = this.getRequester()
					.route("_")
					.metadata(httpRequestMetadata, MediaType.APPLICATION_CBOR)
					.data(session.receive()
							.doFirst(() -> {
								if (log.isInfoEnabled()) {
									log.info("{}\t{}\t101 {} {}", httpHeaders.getFirst("X-Real-IP"), httpRequestMetadata.getMethod(), httpRequestMetadata.getUri(), httpHeaders.getFirst(HttpHeaders.USER_AGENT));
								}
							})
							.map(message -> DataBufferUtils.retain(message.getPayload())), DataBuffer.class)
					.retrieveFlux(DataBuffer.class);
			final Flux<WebSocketMessage> outbound = responseStream.map(buffer -> {
				// first 1 byte of the data is the message type.
				final Type type = Type.values()[buffer.read()];
				return this.createMessage(session, type, buffer);
			});
			return session.send(outbound);
		};
	}

	WebSocketMessage createMessage(WebSocketSession session, Type type, DataBuffer buffer) {
		switch (type) {
			case TEXT:
				return session.textMessage(buffer.toString(StandardCharsets.UTF_8));
			case BINARY:
				return session.binaryMessage(__ -> buffer);
			case PING:
				return session.pingMessage(__ -> buffer);
			case PONG:
				return session.pongMessage(__ -> buffer);
			default:
				throw new IllegalStateException("Unknown type: " + type);
		}
	}

	HttpHeaders setForwardHeaders(ServerHttpRequest request) {
		final URI uri = request.getURI();
		final String scheme = uri.getScheme();
		int port = uri.getPort();
		if (port == -1) {
			if ("http".equals(scheme) || "ws".equals(scheme)) {
				port = 80;
			}
			else if ("https".equals(scheme) || "wss".equals(scheme)) {
				port = 443;
			}
		}
		final HttpHeaders source = request.getHeaders();
		final HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.addAll(source);
		final String remoteAddress = request.getRemoteAddress().getAddress().getHostAddress();
		final String forwarded = String.format("for=%s;host=%s:%d;proto=%s", remoteAddress, uri.getHost(), port, scheme);
		httpHeaders.set("Forwarded", forwarded); httpHeaders.set("X-Real-IP", remoteAddress); return httpHeaders;
	}


	@ExceptionHandler(AuthorizationException.class)
	public Mono<ResponseEntity<Map<String, ?>>> handleAuthorizationException(AuthorizationException e) {
		return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Tsunagu API\"")
				.body(Map.of("error", Map.of("message", e.getMessage(), "type", "invalid_request_error", "code", e.code))));
	}

	void checkAuthorization(ServerHttpRequest request) {
		if (StringUtils.hasText(this.props.getAuthorizationToken())) {
			final String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
			if (StringUtils.hasText(authorization)) {
				if (authorization.startsWith("Bearer") || authorization.startsWith("bearer")) {
					final String token = authorization.replace("Bearer ", "").replace("bearer ", "");
					if (!Objects.equals(this.props.getAuthorizationToken(), token)) {
						throw new AuthorizationException("Incorrect API key provided: " + token, "invalid_api_key");
					}
				}
				else if (authorization.startsWith("Basic") || authorization.startsWith("basic")) {
					final String basic = authorization.replace("Basic ", "").replace("basic ", "");
					final String token = new String(Base64Utils.decodeFromString(basic)).split(":", 2)[1];
					if (!Objects.equals(this.props.getAuthorizationToken(), token)) {
						throw new AuthorizationException("Incorrect API key provided: " + token, "invalid_api_key");
					}
				}
			}
			else {
				throw new AuthorizationException("You didn't provide an API key. You need to provide your API key in an Authorization header using Bearer auth (i.e. Authorization: Bearer YOUR_KEY), or as the password field (with blank username) if you're accesing the API from your browser and are prompted for a username and password.", "");
			}
		}
	}

	public static class AuthorizationException extends RuntimeException {


		private final String code;

		public AuthorizationException(String message, String code) {
			super(message); this.code = code;
		}
	}
}
