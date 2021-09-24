package am.ik.tsunagu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
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
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.annotation.ConnectMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class TsunaguController {
	private final Logger log = LoggerFactory.getLogger(TsunaguController.class);

	private final Set<RSocketRequester> requesters = Collections.newSetFromMap(new ConcurrentHashMap<>());

	private final ObjectMapper objectMapper;

	private final NettyDataBufferFactory dataBufferFactory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);

	public TsunaguController(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@RequestMapping(path = "**")
	public Mono<ResponseEntity<?>> proxy(ServerHttpRequest request) throws Exception {
		final RSocketClient rsocketClient = this.getRequester().rsocketClient();
		final HttpRequestMetadata httpRequestMetadata = new HttpRequestMetadata(request.getMethod(), request.getURI(), request.getHeaders());
		final byte[] metadata = this.objectMapper.writeValueAsBytes(httpRequestMetadata);
		final Flux<Payload> responseStream;
		if (httpRequestMetadata.hasBody()) {
			final Flux<Payload> requestPayload = request.getBody()
					.map(NettyDataBufferFactory::toByteBuf)
					.map(data -> DefaultPayload.create(data, Unpooled.copiedBuffer(metadata)))
					.switchIfEmpty(Mono.fromCallable(() -> DefaultPayload.create(new byte[] {}, metadata)));
			responseStream = rsocketClient.requestChannel(requestPayload);
		}
		else {
			final Mono<Payload> requestPayload = Mono.just(DefaultPayload.create(new byte[] {}, metadata));
			responseStream = rsocketClient.requestStream(requestPayload);
		}
		return responseStream.switchOnFirst(this.handleResponse(httpRequestMetadata)).single();
	}

	BiFunction<Signal<? extends Payload>, Flux<Payload>, Publisher<? extends ResponseEntity<?>>> handleResponse(HttpRequestMetadata httpRequestMetadata) {
		return (signal, flux) -> {
			final byte[] httpResponseMetadataBytes = ByteBufUtil.getBytes(signal.get().metadata());
			final Mono<DataBuffer> bodyMono = DataBufferUtils.join(flux.map(payload -> dataBufferFactory.wrap(payload.data())));
			try {
				final HttpResponseMetadata httpResponseMetadata = this.objectMapper.readValue(httpResponseMetadataBytes, HttpResponseMetadata.class);
				log.info("\nrequest:\t{}\nresponse:\t{}", httpRequestMetadata, httpResponseMetadata);
				return bodyMono.map(body -> ResponseEntity.status(httpResponseMetadata.getStatus())
						.headers(httpResponseMetadata.getHeaders())
						.body(body));
			}
			catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		};
	}

	private RSocketRequester getRequester() {
		if (this.requesters.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No requester found.");
		}
		return this.requesters.iterator().next();
	}

	@ConnectMapping
	public void connect(RSocketRequester requester) {
		requester.rsocket()
				.onClose()
				.doFirst(() -> {
					log.info("Client: {} CONNECTED.", requester);
					requesters.add(requester);
				})
				.doOnError(error -> {
					log.warn("Channel to client {} CLOSED", requester);
				})
				.doFinally(consumer -> {
					requesters.remove(requester);
					log.info("Client {} DISCONNECTED", requesters);
				})
				.subscribe();
	}

	@MessageMapping("version_check")
	public String versionCheck() {
		return "OK";
	}
}
