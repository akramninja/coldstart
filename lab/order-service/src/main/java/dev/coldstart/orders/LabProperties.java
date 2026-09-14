package dev.coldstart.orders;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The lab's own knobs. None of them is a Spring Boot default: those are only ever changed from a
 * scenario file.
 *
 * @param seed size of the dataset written on first startup
 * @param orders how {@code GET /api/orders/{id}} loads an order
 * @param shipping the simulated carrier API
 * @param pricing the promotion engine
 * @param confirmation the simulated e-mail provider
 */
@ConfigurationProperties("coldstart")
public record LabProperties(@DefaultValue Seed seed, @DefaultValue Orders orders, @DefaultValue Shipping shipping,
		@DefaultValue Pricing pricing, @DefaultValue Confirmation confirmation) {

	public record Seed(@DefaultValue("100000") int customers, @DefaultValue("200000") int products,
			@DefaultValue("200000") int orders) {
	}

	/**
	 * @param readModel {@code entity}: lazy entities mapped in the web layer, which needs
	 * open-in-view. {@code projection}: one fetch-join query inside a read-only transaction.
	 */
	public record Orders(@DefaultValue("entity") ReadModel readModel) {
	}

	public enum ReadModel {

		ENTITY, PROJECTION

	}

	/**
	 * @param latency the round trip to the carrier API
	 */
	public record Shipping(@DefaultValue("100ms") Duration latency) {
	}

	/**
	 * @param rules promotion rules checked against every cart line: the CPU cost of a quote
	 */
	public record Pricing(@DefaultValue("10000") int rules) {
	}

	/**
	 * @param providerLatency one call to the e-mail provider
	 */
	public record Confirmation(@DefaultValue("200ms") Duration providerLatency) {
	}

}
