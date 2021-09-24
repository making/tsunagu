package am.ik.tsunagu;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.logging.LogLevel;
import io.rsocket.Payload;
import io.rsocket.util.DefaultPayload;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.tcp.TcpServer;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.annotation.ConnectMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TsunaguController implements BiFunction<NettyInbound, NettyOutbound, Publisher<Void>> {
	private final Logger log = LoggerFactory.getLogger(TsunaguController.class);

	private final Set<RSocketRequester> requesters = Collections.newSetFromMap(new ConcurrentHashMap<>());

	private DisposableServer server;

	@PostConstruct
	public void initTcpServer() {
		log.info("Initializing tcp server");
		this.server =
				TcpServer.create()
						.host("localhost")
						.port(8082)
						.wiretap("am.ik.tsunagu.TcpServer", LogLevel.INFO, AdvancedByteBufFormat.TEXTUAL)
						.handle(this)
						.bindNow();
	}

	@PreDestroy
	public void destroy() {
		log.info("Destroying tcp server");
		if (server != null) {
			this.server.disposeNow();
		}
	}


	@Override
	public Publisher<Void> apply(NettyInbound inbound, NettyOutbound outbound) {
		final Flux<ByteBuffer> input = inbound.receive().asByteBuffer();

		input
				.doOnNext(message -> {
					if (requesters.isEmpty()) {
						log.error("No requester found");
						outbound.send(Mono.just(Unpooled.copiedBuffer("HTTP/1.1 503 SERVICE UNAVAILABLE\n"
										+ "Date: Thu, 23 Sep 2021 11:59:08 GMT\n"
										+ "Content-Type: text/plain; charset=utf-8\n"
										+ "Content-Length: 0\n"
										+ "Connection: keep-alive\n"
										+ "Access-Control-Allow-Origin: *\n"
										+ "Access-Control-Allow-Credentials: true\n"
										+ "\n\n", StandardCharsets.UTF_8)))
								.then()
								.subscribe();
					}
					else {
						final RSocketRequester requester = requesters.iterator().next();
						final Flux<ByteBuf> response = requester.rsocketClient()
								.requestStream(Mono.just(DefaultPayload.create(message)))
								.map(Payload::data);
						outbound.send(response)
								.then()
								.subscribe();
					}
				})
				.subscribe();
		return Flux.never();
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
