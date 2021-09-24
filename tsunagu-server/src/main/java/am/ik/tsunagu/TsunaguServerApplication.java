package am.ik.tsunagu;

import reactor.core.publisher.Hooks;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TsunaguServerApplication {

	public static void main(String[] args) {
		Hooks.onErrorDropped(e -> { /* https://github.com/rsocket/rsocket-java/issues/1018 */});
		SpringApplication.run(TsunaguServerApplication.class, args);
	}

}
