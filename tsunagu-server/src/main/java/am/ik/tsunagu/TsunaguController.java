package am.ik.tsunagu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.rsocket.Payload;
import io.rsocket.core.RSocketClient;
import io.rsocket.util.DefaultPayload;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.annotation.ConnectMapping;
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

	private final Set<RSocketRequester> requesters = Collections.newSetFromMap(new ConcurrentHashMap<>());

	private final ObjectMapper objectMapper;

	private final TsunaguProps props;

	public TsunaguController(ObjectMapper objectMapper, TsunaguProps props) {
		this.objectMapper = objectMapper;
		this.props = props;
	}


	private RSocketRequester getRequester() {
		if (this.requesters.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No requester found.");
		}
		// TODO Load Balancing
		return this.requesters.iterator().next();
	}

	@RequestMapping(path = "**")
	public Mono<Void> proxy(ServerHttpRequest request, ServerHttpResponse response) throws Exception {
		final RSocketClient rsocketClient = this.getRequester().rsocketClient();
		final HttpRequestMetadata httpRequestMetadata = new HttpRequestMetadata(request.getMethod(), request.getURI(), request.getHeaders());
		final byte[] metadata = this.objectMapper.writeValueAsBytes(httpRequestMetadata);
		final Flux<Payload> responseStream;
		if (httpRequestMetadata.hasBody()) {
			final AtomicBoolean headerSent = new AtomicBoolean(false);
			final Flux<Payload> requestPayload = request.getBody()
					.map(NettyDataBufferFactory::toByteBuf)
					.map(data -> DefaultPayload.create(data, headerSent.compareAndSet(false, true) ? Unpooled.copiedBuffer(metadata) : Unpooled.EMPTY_BUFFER))
					.switchIfEmpty(Mono.fromCallable(() -> DefaultPayload.create(new byte[] {}, metadata)));
			responseStream = rsocketClient.requestChannel(requestPayload);
		}
		else {
			final Mono<Payload> requestPayload = Mono.just(DefaultPayload.create(new byte[] {}, metadata));
			responseStream = rsocketClient.requestStream(requestPayload);
		}
		return responseStream.switchOnFirst(this.handleResponse(httpRequestMetadata, response)).then();
	}

	BiFunction<Signal<? extends Payload>, Flux<Payload>, Publisher<? extends Void>> handleResponse(HttpRequestMetadata httpRequestMetadata, ServerHttpResponse response) {
		return (signal, flux) -> {
			final byte[] httpResponseMetadataBytes = ByteBufUtil.getBytes(signal.get().metadata());
			final Flux<DataBuffer> body = flux.map(payload -> response.bufferFactory().wrap(payload.getData()));
			try {
				final HttpResponseMetadata httpResponseMetadata = this.objectMapper.readValue(httpResponseMetadataBytes, HttpResponseMetadata.class);
				log.info("\nrequest:\t{}\nresponse:\t{}", httpRequestMetadata, httpResponseMetadata);
				response.setStatusCode(httpResponseMetadata.getStatus());
				response.getHeaders().addAll(httpResponseMetadata.getHeaders());
				return response.writeWith(body);
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		};
	}

	@ConnectMapping
	public void connect(RSocketRequester requester, @org.springframework.messaging.handler.annotation.Payload Map<String, String> data) {
		if (!Objects.equals(this.props.getToken(), data.get("token"))) {
			requester.rsocketClient().fireAndForget(Mono.just(DefaultPayload.create("Token is wrong."))).subscribe();
			return;
		}
		requester.rsocket()
				.onClose()
				.doFirst(() -> {
					log.info("Client: {} connected", requester);
					requesters.add(requester);
				})
				.doOnError(error -> {
					log.warn("Client: " + requester + " error", error);
				})
				.doFinally(consumer -> {
					requesters.remove(requester);
					log.info("Client: {} disconnected", requesters);
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
			final HttpRequestMetadata httpRequestMetadata = new HttpRequestMetadata(request.getMethod(), request.getURI(), request.getHeaders());
			try {
				final byte[] metadata = this.objectMapper.writeValueAsBytes(httpRequestMetadata);
				final AtomicBoolean headerSent = new AtomicBoolean(false);
				final Flux<Payload> inbound = session.receive()
						.map(message -> NettyDataBufferFactory.toByteBuf(message.getPayload()))
						.map(data -> DefaultPayload.create(data.retain(), headerSent.compareAndSet(false, true) ? Unpooled.copiedBuffer(metadata) : Unpooled.EMPTY_BUFFER));
				final RSocketClient rsocketClient = this.getRequester().rsocketClient();
				final Flux<WebSocketMessage> outbound = rsocketClient.requestChannel(inbound)
						.map(payload -> this.createMessage(session, payload));
				return session.send(outbound);
			}
			catch (JsonProcessingException e) {
				return Mono.error(e);
			}
		};
	}

	WebSocketMessage createMessage(WebSocketSession session, Payload payload) {
		final Type type = Type.values()[payload.getMetadata().get()];
		switch (type) {
			case TEXT:
				return session.textMessage(payload.getDataUtf8());
			case BINARY:
				return session.binaryMessage(factory -> factory.wrap(payload.getData()));
			case PING:
				return session.pingMessage(factory -> factory.wrap(payload.getData()));
			case PONG:
				return session.pongMessage(factory -> factory.wrap(payload.getData()));
			default:
				throw new IllegalStateException("Unknown type: " + type);
		}
	}
}
