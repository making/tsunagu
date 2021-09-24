package am.ik.tsunagu;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "tsunagu")
@ConstructorBinding
public class TsunaguProps {
	private final URI remote;

	private final URI upstream;

	private final boolean preserveHost;

	public TsunaguProps(URI remote, URI upstream, @DefaultValue("false") boolean preserveHost) {
		this.remote = remote;
		this.upstream = upstream;
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
}
