package dev.coldstart.orders.order;

import java.math.BigDecimal;
import java.util.List;

import dev.coldstart.orders.pricing.Quote;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmationEmailTests {

	@Test
	void aTypicalOrderRendersAbout15Kilobytes() {
		// Two lines with 1,400-character descriptions: the middle of the seeded catalog and of k6's orders.
		PlacedOrder.Line line = new PlacedOrder.Line("SKU-0000001", "Compact Speaker 120", "x".repeat(1_400), 2,
				new BigDecimal("49.90"));
		Quote quote = new Quote(List.of(), new BigDecimal("199.60"), BigDecimal.ZERO, new BigDecimal("39.92"),
				new BigDecimal("239.52"));

		ConfirmationEmail email = ConfirmationEmail.render(
				new PlacedOrder(1, "Camille Martin", "customer1@example.com", "FR", quote, List.of(line, line)));

		assertThat(email.html().length()).isBetween(14_000, 16_000);
	}

}
