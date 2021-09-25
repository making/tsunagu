package am.ik.tsunagu;

import java.util.function.Function;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;

@Configuration
public class WebSocketConfig {
	private final Function<ServerHttpRequest, WebSocketHandler> webSocketHandlerFactory;

	public WebSocketConfig(Function<ServerHttpRequest, WebSocketHandler> webSocketHandlerFactory) {
		this.webSocketHandlerFactory = webSocketHandlerFactory;
	}

	@Bean
	public HandlerMapping webSocketHandlerMapping() {
		return new WebSocketHandlerMapping(this.webSocketHandlerFactory, Ordered.HIGHEST_PRECEDENCE);
	}
}
