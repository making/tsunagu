package am.ik.tsunagu;

import reactor.core.publisher.Hooks;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
						types = { HttpHeaders.class, HttpRequestMetadata.class, HttpResponseMetadata.class },
						typeNames = {
								"org.springframework.cloud.sleuth.autoconfig.zipkin2.ZipkinActiveMqSenderConfiguration",
								"org.springframework.cloud.sleuth.autoconfig.zipkin2.ZipkinRabbitSenderConfiguration",
								"org.springframework.cloud.sleuth.autoconfig.zipkin2.ZipkinKafkaSenderConfiguration"
						}
				)
		})
public class TsunaguServerApplication {

	public static void main(String[] args) {
		Hooks.onErrorDropped(e -> { /* https://github.com/rsocket/rsocket-java/issues/1018 */});
		SpringApplication.run(TsunaguServerApplication.class, args);
	}

}
