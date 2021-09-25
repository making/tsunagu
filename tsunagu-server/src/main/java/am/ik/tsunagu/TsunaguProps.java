package am.ik.tsunagu;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

@ConfigurationProperties(prefix = "tsunagu")
@ConstructorBinding
public class TsunaguProps {
	private final String token;

	private final Logger log = LoggerFactory.getLogger(TsunaguProps.class);

	public TsunaguProps(String token) {
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
}
