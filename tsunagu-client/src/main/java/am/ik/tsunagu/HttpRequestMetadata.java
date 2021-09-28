package am.ik.tsunagu;

import java.net.URI;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.CollectionUtils;

public class HttpRequestMetadata {
	private final HttpMethod method;

	private final URI uri;

	private final HttpHeaders headers;

	public HttpRequestMetadata(
			@JsonProperty("m") HttpMethod method,
			@JsonProperty("u") URI uri,
			@JsonProperty("h") HttpHeaders headers) {
		this.method = method;
		this.uri = uri;
		this.headers = headers;
	}

	public HttpMethod getMethod() {
		return method;
	}

	public URI getUri() {
		return uri;
	}

	public HttpHeaders getHeaders() {
		return headers;
	}

	@Override
	public String toString() {
		return "{method=" + method +
				", uri=" + uri +
				", headers=" + headers +
				'}';
	}

	@JsonIgnore
	public boolean isWebSocketRequest() {
		final String upgrade = this.headers.getUpgrade();
		if (upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
			return false;
		}
		final List<String> connections = this.headers.getConnection();
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
