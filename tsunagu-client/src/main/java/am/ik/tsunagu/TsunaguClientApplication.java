package am.ik.tsunagu;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.JavaInfo;
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
						types = {
								HttpHeaders.class,
								HttpRequestMetadata.class,
								HttpResponseMetadata.class,
								JavaInfo.class,
								JavaInfo.JavaRuntimeEnvironmentInfo.class,
								JavaInfo.JavaVirtualMachineInfo.class
						},
						access = { DECLARED_FIELDS, DECLARED_METHODS, DECLARED_CONSTRUCTORS, PUBLIC_FIELDS, PUBLIC_METHODS, PUBLIC_CONSTRUCTORS }
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
