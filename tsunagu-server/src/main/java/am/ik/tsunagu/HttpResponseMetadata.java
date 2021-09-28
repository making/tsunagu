package am.ik.tsunagu;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

public class HttpResponseMetadata {
	private final HttpStatus status;

	private final HttpHeaders headers;

	public HttpResponseMetadata(
			@JsonProperty("s") HttpStatus status,
			@JsonProperty("h") HttpHeaders headers) {
		this.status = status;
		this.headers = headers;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public HttpHeaders getHeaders() {
		return headers;
	}

	@Override
	public String toString() {
		return "{status=" + status +
				", headers=" + headers +
				'}';
	}
}
