package am.ik.tsunagu;

import java.net.URI;

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

	public TsunaguProps(URI remote, URI upstream, @DefaultValue("false") boolean preserveHost) {
		this.remote = fixPort(remote);
		this.upstream = fixPort(upstream);
		this.preserveHost = preserveHost;
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
