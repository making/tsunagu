package am.ik.tsunagu;

import java.io.IOException;
import java.io.UncheckedIOException;

import reactor.core.publisher.Hooks;
import reactor.netty.http.Http11SslContextSpec;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.netty.NettyServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.nativex.hint.NativeHint;
import org.springframework.nativex.hint.TypeHint;

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
						types = { HttpHeaders.class, HttpRequestMetadata.class, HttpResponseMetadata.class }
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
	@ConditionalOnProperty(name = { "tsunagu.tls.crt", "tsunagu.tls.key" })
	public NettyServerCustomizer customizer(TsunaguProps props) {
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
