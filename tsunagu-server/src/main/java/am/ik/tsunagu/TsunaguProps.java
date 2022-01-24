package am.ik.tsunagu;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;
import org.springframework.core.io.ContextResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

@ConfigurationProperties(prefix = "tsunagu")
@ConstructorBinding
public class TsunaguProps {
	private final String token;

	private final Tls tls;

	private final Map<String, String> acmeChallenge;

	private final Logger log = LoggerFactory.getLogger(TsunaguProps.class);

	public TsunaguProps(String token, Tls tls, Map<String, String> acmeChallenge) {
		this.tls = tls;
		this.acmeChallenge = acmeChallenge;
		if (token == null) {
			this.token = UUID.randomUUID().toString();
			log.info("Token = {}", this.token);
		}
		else {
			this.token = token;
		}
	}

	public String getToken() {
		return token;
	}

	public Tls getTls() {
		return tls;
	}

	public Map<String, String> getAcmeChallenge() {
		return acmeChallenge == null ? Collections.emptyMap() : acmeChallenge;
	}

	@ConstructorBinding
	public static class Tls {
		private final Resource crt;

		private final Resource key;

		public Tls(String crt, String key) {
			final FileSystemResourceLoader loader = new FileSystemResourceLoader();
			this.crt = loader.getResource(crt);
			this.key = loader.getResource(key);
		}

		public Resource getCrt() {
			return crt;
		}

		public Resource getKey() {
			return key;
		}

		public static class FileSystemResourceLoader extends DefaultResourceLoader {
			@Override
			protected Resource getResourceByPath(String path) {
				return new FileSystemContextResource(path);
			}

			private static class FileSystemContextResource extends FileSystemResource implements ContextResource {

				public FileSystemContextResource(String path) {
					super(path);
				}

				@Override
				public String getPathWithinContext() {
					return getPath();
				}
			}
		}
	}
}
