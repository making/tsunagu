package am.ik.tsunagu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(TsunaguProps.class)
public class TsunaguClientApplication {

	public static void main(String[] args) {
		SpringApplication.run(TsunaguClientApplication.class, args);
	}

}
