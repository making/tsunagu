package am.ik.tsunagu;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

public class TsunaguConnectionVerifier {
	private final String requesterId;

	private final ApplicationEventPublisher eventPublisher;

	private final RestTemplate restTemplate;

	private final Logger log = LoggerFactory.getLogger(TsunaguConnectionVerifier.class);


	public TsunaguConnectionVerifier(String requesterId, ApplicationEventPublisher eventPublisher, TsunaguProps props) {
		this.requesterId = requesterId;
		this.eventPublisher = eventPublisher;
		final String url = props.getRemote().toString()
				.replace("wss://", "https://")
				.replace("ws://", "http://");
		this.restTemplate = new RestTemplateBuilder()
				.rootUri(UriComponentsBuilder.fromHttpUrl(url).replacePath("").build(false).toString())
				.build();
	}

	public void verifyConnection() {
		log.debug("verify connection");
		try {
			final String[] requesters = this.restTemplate.getForObject("/.tsunagu/requesters", String[].class);
			if (Arrays.asList(requesters).contains(this.requesterId)) {
				log.debug("verification ok requester(client={}, server={})", this.requesterId, Arrays.toString(requesters));
				AvailabilityChangeEvent.publish(this.eventPublisher, requesters, LivenessState.CORRECT);
			}
			else {
				log.warn("verification failed requester(client={}, server={})", this.requesterId, Arrays.toString(requesters));
				AvailabilityChangeEvent.publish(this.eventPublisher, requesters, LivenessState.BROKEN);
			}
		}
		catch (RuntimeException e) {
			log.warn("verification failed", e);
			AvailabilityChangeEvent.publish(this.eventPublisher, e, LivenessState.BROKEN);
		}
	}
}
