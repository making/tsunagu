package am.ik.tsunagu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBufUtil;
import io.rsocket.Payload;
import io.rsocket.util.DefaultPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
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

	private final DataBufferFactory dataBufferFactory = DefaultDataBufferFactory.sharedInstance;

	public TsunaguController(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@RequestMapping(path = "**")
	public Mono<ResponseEntity<?>> proxy(ServerHttpRequest request) {
		final RSocketRequester requester = this.getRequester();
		final HttpRequestMetadata httpRequestMetadata = new HttpRequestMetadata(request.getMethod(), request.getURI(), request.getHeaders());
		final Mono<Payload> requestPayload = Mono.fromCallable(() -> this.objectMapper.writeValueAsBytes(httpRequestMetadata))
				.map(metadata -> DefaultPayload.create(new byte[] {}, metadata));
		final Flux<Payload> responseStream = requester.rsocketClient().requestStream(requestPayload);
		return responseStream.<ResponseEntity<?>>switchOnFirst((signal, flux) -> {
			final byte[] httpResponseMetadataBytes = ByteBufUtil.getBytes(signal.get().metadata());
			final Mono<DataBuffer> bodyMono = DataBufferUtils.join(flux.map(payload -> dataBufferFactory.wrap(payload.getData())));
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
		}).single();
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
