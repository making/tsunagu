package am.ik.tsunagu;

import java.net.URI;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

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

	public boolean hasBody() {
		return this.method == HttpMethod.POST || this.method == HttpMethod.PUT || this.method == HttpMethod.PATCH;
	}

	@Override
	public String toString() {
		return "{method=" + method +
				", uri=" + uri +
				", headers=" + headers +
				'}';
	}
}
