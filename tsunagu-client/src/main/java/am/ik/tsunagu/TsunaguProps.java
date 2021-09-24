package am.ik.tsunagu;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

@ConfigurationProperties(prefix = "tsunagu")
@ConstructorBinding
public class TsunaguProps {
	private final URI remote;

	private final URI upstream;

	public TsunaguProps(URI remote, URI upstream) {
		this.remote = remote;
		this.upstream = upstream;
	}

	public URI getRemote() {
		return remote;
	}

	public URI getUpstream() {
		return upstream;
	}
}
