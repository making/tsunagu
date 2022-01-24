package am.ik.tsunagu;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.slf4j.LoggerFactory;
import reactor.core.publisher.Hooks;
import reactor.netty.http.Http11SslContextSpec;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.nativex.hint.NativeHint;
import org.springframework.nativex.hint.TypeHint;

import static org.springframework.nativex.hint.TypeAccess.DECLARED_CONSTRUCTORS;
import static org.springframework.nativex.hint.TypeAccess.DECLARED_FIELDS;
import static org.springframework.nativex.hint.TypeAccess.DECLARED_METHODS;
import static org.springframework.nativex.hint.TypeAccess.PUBLIC_CONSTRUCTORS;
import static org.springframework.nativex.hint.TypeAccess.PUBLIC_FIELDS;
import static org.springframework.nativex.hint.TypeAccess.PUBLIC_METHODS;

@SpringBootApplication
@EnableConfigurationProperties(TsunaguProps.class)
@NativeHint(
		options = {
				"--enable-http",
				"--enable-https",
				"--enable-all-security-services",
				"-Dfile.encoding=UTF-8"
		},
		types = {
				@TypeHint(
						types = { HttpHeaders.class, HttpRequestMetadata.class, HttpResponseMetadata.class },
						access = { DECLARED_FIELDS, DECLARED_METHODS, DECLARED_CONSTRUCTORS, PUBLIC_FIELDS, PUBLIC_METHODS, PUBLIC_CONSTRUCTORS }
				)
		})
public class TsunaguServerApplication {

	public static void main(String[] args) {
		System.out.println(" _______\n"
				+ "|__   __|\n"
				+ "   | |___ _   _ _ __   __ _  __ _ _   _\n"
				+ "   | / __| | | | '_ \\ / _` |/ _` | | | |\n"
				+ "   | \\__ \\ |_| | | | | (_| | (_| | |_| |\n"
				+ "   |_|___/\\__,_|_| |_|\\__,_|\\__, |\\__,_|\n"
				+ "                             __/ |\n"
				+ "                            |___/\n"
				+ " :: Tsunagu Server ::\n");
		Hooks.onErrorDropped(e -> { /* https://github.com/rsocket/rsocket-java/issues/1018 */});
		SpringApplication.run(TsunaguServerApplication.class, args);
	}

	@Bean
	public NettyServerCustomizer customizer(TsunaguProps props) {
		final TsunaguProps.Tls tls = props.getTls();
		// @ConditionalOnProperty(name = { "tsunagu.tls.crt", "tsunagu.tls.key" }) is not working in the native image ???
		if (tls == null || tls.getCrt() == null || tls.getKey() == null) {
			return httpServer -> httpServer;
		}
		LoggerFactory.getLogger(TsunaguServerApplication.class)
				.info("TLS is enabled (crt = {}, key = {})", tls.getCrt(), tls.getKey());
		return httpServer -> httpServer
				.secure(sslContextSpec -> {
					try {
						final Http11SslContextSpec spec = Http11SslContextSpec.forServer(props.getTls().getCrt().getFile(), props.getTls().getKey().getFile());
						sslContextSpec.sslContext(spec);
					}
					catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				});
	}
}
