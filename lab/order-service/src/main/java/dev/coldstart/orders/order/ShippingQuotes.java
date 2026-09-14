package dev.coldstart.orders.order;

import java.math.BigDecimal;
import java.time.Duration;

import dev.coldstart.orders.LabProperties;

import org.springframework.stereotype.Component;

/**
 * Stands in for the carrier API every order page calls. Only its latency matters for Default 3:
 * with open-in-view, the request keeps its database connection while it waits here.
 * <p>
 * A sleep blocks the thread exactly as a socket read would, without a second service or network
 * noise in the lab.
 */
@Component
class ShippingQuotes {

	private static final BigDecimal BASE_PRICE = new BigDecimal("4.90");

	private static final BigDecimal PRICE_PER_ITEM = new BigDecimal("0.75");

	private final Duration latency;

	ShippingQuotes(LabProperties properties) {
		this.latency = properties.shipping().latency();
	}

	ShippingQuote quote(String country, int items) {
		if (latency.isPositive()) {
			try {
				Thread.sleep(latency);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for the carrier", interrupted);
			}
		}
		BigDecimal price = BASE_PRICE.add(PRICE_PER_ITEM.multiply(BigDecimal.valueOf(items)));
		return new ShippingQuote("colis-express", price, "FR".equals(country) ? 2 : 4);
	}

}
