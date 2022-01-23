package am.ik.tsunagu;

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
						types = { HttpHeaders.class, HttpRequestMetadata.class, HttpResponseMetadata.class }
				)
		})
public class TsunaguClientApplication {

	public static void main(String[] args) {
		System.out.println(" _______\n"
				+ "|__   __|\n"
				+ "   | |___ _   _ _ __   __ _  __ _ _   _\n"
				+ "   | / __| | | | '_ \\ / _` |/ _` | | | |\n"
				+ "   | \\__ \\ |_| | | | | (_| | (_| | |_| |\n"
				+ "   |_|___/\\__,_|_| |_|\\__,_|\\__, |\\__,_|\n"
				+ "                             __/ |\n"
				+ "                            |___/\n"
				+ " :: Tsunagu Client ::\n");
		SpringApplication.run(TsunaguClientApplication.class, args);
	}

}
