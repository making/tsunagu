package am.ik.tsunagu;

import java.util.List;
import java.util.function.Function;

import reactor.core.publisher.Mono;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.server.ServerWebExchange;

public class WebSocketHandlerMapping extends AbstractHandlerMapping {
	private final Function<ServerHttpRequest, WebSocketHandler> webSocketHandlerFactory;

	public WebSocketHandlerMapping(Function<ServerHttpRequest, WebSocketHandler> webSocketHandlerFactory, int order) {
		this.webSocketHandlerFactory = webSocketHandlerFactory;
		super.setOrder(order);
	}

	@Override
	protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
		final ServerHttpRequest request = exchange.getRequest();
		if (isWebSocketRequest(request.getHeaders())) {
			final WebSocketHandler webSocketHandler = webSocketHandlerFactory.apply(request);
			return Mono.just(webSocketHandler);
		}
		return Mono.empty();
	}

	static boolean isWebSocketRequest(HttpHeaders headers) {
		final String upgrade = headers.getUpgrade();
		if (upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
			return false;
		}
		final List<String> connections = headers.getConnection();
		if (CollectionUtils.isEmpty(connections)) {
			return false;
		}
		for (String c : connections) {
			if (c.equalsIgnoreCase("upgrade")) {
				return true;
			}
		}
		return false;
	}
}
