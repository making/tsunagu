package am.ik.tsunagu;

import java.time.Duration;

import io.netty.handler.logging.LogLevel;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.util.DefaultPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.netty.NettyInbound;
import reactor.netty.NettyOutbound;
import reactor.netty.tcp.TcpClient;
import reactor.netty.transport.logging.AdvancedByteBufFormat;
import reactor.util.retry.Retry;

import org.springframework.boot.CommandLineRunner;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketRequester.Builder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class TsunaguConnector implements RSocket, CommandLineRunner {
	private final RSocketRequester requester;

	private final TcpClient tcpClient;

	private final Logger log = LoggerFactory.getLogger(TsunaguConnector.class);

	public TsunaguConnector(Builder requesterBuilder, WebClient.Builder webClientBuilder, TsunaguProps props) {
		this.requester = requesterBuilder
				.rsocketConnector(connector -> connector
						.reconnect(Retry.fixedDelay(Long.MAX_VALUE, Duration.ofSeconds(1))
								.doBeforeRetry(s -> log.info("Reconnect: {}", s)))
						.acceptor((setup, sendingSocket) -> Mono.just(TsunaguConnector.this)))
				.websocket(props.getRemote());
		this.tcpClient = TcpClient.create()
				.host(props.getUpstream().getHost())
				.port(props.getUpstream().getPort())
				.wiretap("am.ik.tsunagu.TcpClient", LogLevel.INFO, AdvancedByteBufFormat.TEXTUAL);
	}

	@Override
	public Flux<Payload> requestStream(Payload payload) {
		return this.tcpClient.connect()
				.flatMapMany(connection -> {
					final NettyOutbound outbound = connection.outbound();
					final NettyInbound inbound = connection.inbound();
					final Flux<Payload> response = inbound.receive().asByteBuffer()
							.map(DefaultPayload::create);
					return outbound.send(Mono.just(payload.data()))
							.then()
							.thenMany(response);
				}).log("requestStream");
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
