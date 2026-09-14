package dev.coldstart.orders.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import dev.coldstart.orders.LabProperties;
import dev.coldstart.orders.catalog.Product;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Default 2. Finds the best promotion for every cart line by checking every rule: no I/O, no lock,
 * a cost proportional to lines × rules. On a CPU-limited pod, this is the work that gets throttled.
 * <p>
 * Rules come from a fixed seed, so every pod and every run prices a cart the same way.
 */
@Component
public class PricingEngine {

	private static final BigDecimal[] RATES = { new BigDecimal("0.02"), new BigDecimal("0.05"),
			new BigDecimal("0.08"), new BigDecimal("0.10"), new BigDecimal("0.12"), new BigDecimal("0.15"),
			new BigDecimal("0.20") };

	private final List<PromotionRule> rules;

	@Autowired
	PricingEngine(LabProperties properties) {
		this(properties.pricing().rules());
	}

	PricingEngine(int ruleCount) {
		this.rules = generateRules(ruleCount);
	}

	public Quote quote(CustomerTier tier, String country, List<PricingLine> lines) {
		BigDecimal vatRate = VatRates.of(country);
		List<Quote.Line> quoted = new ArrayList<>(lines.size());
		BigDecimal subtotal = BigDecimal.ZERO;
		BigDecimal discount = BigDecimal.ZERO;
		for (PricingLine line : lines) {
			BigDecimal gross = line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()));
			PromotionRule best = null;
			BigDecimal bestDiscount = BigDecimal.ZERO;
			for (PromotionRule rule : rules) {
				if (rule.appliesTo(line, tier, country)) {
					BigDecimal candidate = gross.multiply(rule.rate()).setScale(2, RoundingMode.HALF_EVEN);
					if (candidate.compareTo(bestDiscount) > 0) {
						best = rule;
						bestDiscount = candidate;
					}
				}
			}
			quoted.add(new Quote.Line(line.sku(), line.quantity(), line.unitPrice(), (best != null) ? best.id() : null,
					bestDiscount, gross.subtract(bestDiscount)));
			subtotal = subtotal.add(gross);
			discount = discount.add(bestDiscount);
		}
		BigDecimal net = subtotal.subtract(discount);
		BigDecimal tax = net.multiply(vatRate).setScale(2, RoundingMode.HALF_EVEN);
		return new Quote(quoted, subtotal, discount, tax, net.add(tax));
	}

	private static List<PromotionRule> generateRules(int count) {
		Random random = new Random(42);
		CustomerTier[] tiers = CustomerTier.values();
		List<PromotionRule> generated = new ArrayList<>(count);
		for (int id = 1; id <= count; id++) {
			// One rule in five for every category, one in three for a single country.
			String category = (random.nextInt(5) == 0) ? null : pick(random, Product.CATEGORIES);
			String country = (random.nextInt(3) == 0) ? pick(random, VatRates.COUNTRIES) : null;
			generated.add(new PromotionRule(id, category, tiers[random.nextInt(tiers.length)], 1 + random.nextInt(10),
					country, RATES[random.nextInt(RATES.length)]));
		}
		return List.copyOf(generated);
	}

	private static String pick(Random random, List<String> values) {
		return values.get(random.nextInt(values.size()));
	}

}
