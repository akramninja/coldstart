package dev.coldstart.orders.order;

import java.time.Duration;

import dev.coldstart.orders.LabProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Default 4. Runs on Spring Boot's auto-configured {@code applicationTaskExecutor}, sized by nothing
 * in this code: 8 threads and an unbounded queue unless a scenario says otherwise. With a 200 ms
 * provider, those 8 threads send about 40 e-mails per second.
 */
@Component
class ConfirmationSender {

	private final OrderPlacement placement;

	private final Duration providerLatency;

	private final Counter sent;

	ConfirmationSender(OrderPlacement placement, LabProperties properties, MeterRegistry registry) {
		this.placement = placement;
		this.providerLatency = properties.confirmation().providerLatency();
		this.sent = OrderConfirmations.counter(registry, "sent");
	}

	@Async
	public void send(ConfirmationEmail email) {
		if (!deliver(email)) {
			return;
		}
		placement.recordConfirmation(email.orderId(), ConfirmationStatus.SENT);
		sent.increment();
	}

	/**
	 * Stands in for the e-mail provider's API.
	 * @return {@code false} when the JVM is shutting down: the e-mail is not sent and the order stays
	 * {@code QUEUED}, like every task still waiting in the queue
	 */
	private boolean deliver(ConfirmationEmail email) {
		if (providerLatency.isPositive()) {
			try {
				Thread.sleep(providerLatency);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return true;
	}

}
