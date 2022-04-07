package am.ik.tsunagu;

import java.net.URI;
import java.util.Collections;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.web.util.UriComponentsBuilder;

@ConfigurationProperties(prefix = "tsunagu")
@ConstructorBinding
public class TsunaguProps {
	private final URI remote;

	private final URI upstream;

	private final boolean preserveHost;

	private final String token;

	private final Integer webSocketMaxFramePayloadLength;

	private final Map<String, String> hostMap;

	private final Map<String, String> pathToHostMap;

	public TsunaguProps(URI remote, URI upstream, @DefaultValue("false") boolean preserveHost, String token, @DefaultValue("655350") Integer webSocketMaxFramePayloadLength, Map<String, String> hostMap, Map<String, String> pathToHostMap) {
		this.remote = fixPort(remote);
		this.upstream = fixPort(upstream);
		this.preserveHost = preserveHost;
		this.token = token;
		this.webSocketMaxFramePayloadLength = webSocketMaxFramePayloadLength;
		this.hostMap = hostMap == null ? Map.of() : Collections.unmodifiableMap(hostMap);
		this.pathToHostMap = pathToHostMap == null ? Map.of() : Collections.unmodifiableMap(pathToHostMap);
	}

	public URI getRemote() {
		return remote;
	}

	public URI getUpstream() {
		return upstream;
	}

	public boolean isPreserveHost() {
		return preserveHost;
	}

	public String getToken() {
		return token;
	}

	public Integer getWebSocketMaxFramePayloadLength() {
		return webSocketMaxFramePayloadLength;
	}

	public Map<String, String> getHostMap() {
		return hostMap;
	}

	public Map<String, String> getPathToHostMap() {
		return pathToHostMap;
	}

	static URI fixPort(URI uri) {
		if (uri.getPort() != -1) {
			return uri;
		}
		final String scheme = uri.getScheme();
		final int port;
		if ("http".equals(scheme) || "ws".equals(scheme)) {
			port = 80;
		}
		else if ("https".equals(scheme) || "wss".equals(scheme)) {
			port = 443;
		}
		else {
			return uri;
		}
		return UriComponentsBuilder.fromUri(uri).port(port).build().toUri();
	}
}
