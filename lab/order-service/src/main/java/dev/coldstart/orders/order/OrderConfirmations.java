package dev.coldstart.orders.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

import static dev.coldstart.orders.order.ConfirmationStatus.DEFERRED;
import static dev.coldstart.orders.order.ConfirmationStatus.QUEUED;

/**
 * Hands the confirmation to the executor, and decides what happens when the executor says no. With
 * Spring Boot's unbounded queue it never says no, so the catch block never runs.
 */
@Component
class OrderConfirmations {

	private final ConfirmationSender sender;

	private final OrderPlacement placement;

	private final Counter queued;

	private final Counter deferred;

	OrderConfirmations(ConfirmationSender sender, OrderPlacement placement, MeterRegistry registry) {
		this.sender = sender;
		this.placement = placement;
		this.queued = counter(registry, "queued");
		this.deferred = counter(registry, "deferred");
	}

	ConfirmationStatus dispatch(PlacedOrder order) {
		ConfirmationEmail email = ConfirmationEmail.render(order);
		try {
			sender.send(email);
			queued.increment();
			return QUEUED;
		}
		catch (TaskRejectedException executorFull) {
			placement.recordConfirmation(order.id(), DEFERRED);   // the order is committed, only the e-mail waits
			deferred.increment();
			return DEFERRED;
		}
	}

	/**
	 * {@code coldstart_confirmations_total{outcome="queued|sent|deferred"}}. The gap between queued
	 * and sent is the backlog held in the heap.
	 */
	static Counter counter(MeterRegistry registry, String outcome) {
		return Counter.builder("coldstart.confirmations")
			.description("Order confirmations by outcome")
			.tag("outcome", outcome)
			.register(registry);
	}

}
