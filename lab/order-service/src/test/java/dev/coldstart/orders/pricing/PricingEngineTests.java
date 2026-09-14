package dev.coldstart.orders.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PricingEngineTests {

	private static final List<PricingLine> CART = List.of(
			new PricingLine("SKU-0000001", "audio", new BigDecimal("149.90"), 3),
			new PricingLine("SKU-0000002", "books", new BigDecimal("12.50"), 10));

	@Test
	void sameRulesSameQuote() {
		assertThat(new PricingEngine(2000).quote(CustomerTier.GOLD, "FR", CART))
			.isEqualTo(new PricingEngine(2000).quote(CustomerTier.GOLD, "FR", CART));
	}

	@Test
	void totalIsNetPlusVat() {
		Quote quote = new PricingEngine(2000).quote(CustomerTier.SILVER, "DE", CART);

		BigDecimal net = quote.subtotal().subtract(quote.discount());
		assertThat(quote.subtotal()).isEqualByComparingTo("574.70");
		assertThat(quote.tax()).isEqualByComparingTo(net.multiply(new BigDecimal("0.19")).setScale(2, RoundingMode.HALF_EVEN));
		assertThat(quote.total()).isEqualByComparingTo(net.add(quote.tax()));
	}

	@Test
	void higherTiersNeverPayMore() {
		PricingEngine engine = new PricingEngine(2000);

		assertThat(engine.quote(CustomerTier.GOLD, "FR", CART).total())
			.isLessThanOrEqualTo(engine.quote(CustomerTier.BRONZE, "FR", CART).total());
	}

	@Test
	void noRuleNoDiscount() {
		Quote quote = new PricingEngine(0).quote(CustomerTier.GOLD, "FR", CART);

		assertThat(quote.discount()).isEqualByComparingTo("0");
		assertThat(quote.lines()).allSatisfy((line) -> assertThat(line.promotionId()).isNull());
	}

}
