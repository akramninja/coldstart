package dev.coldstart.orders.lab;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

import dev.coldstart.orders.catalog.Product;
import dev.coldstart.orders.pricing.CustomerTier;
import dev.coldstart.orders.pricing.VatRates;

/**
 * Deterministic data: every value is a pure function of an id. Two runs, two pods and the load
 * generator all agree on what {@code SKU-0000042} is, without sharing any state.
 */
final class Dataset {

	private static final List<String> FIRST_NAMES = List.of("Camille", "Lucas", "Léa", "Hugo", "Chloé", "Louis",
			"Emma", "Jules", "Inès", "Nathan", "Sofia", "Karim", "Yasmine", "Thomas", "Manon", "Adam");

	private static final List<String> LAST_NAMES = List.of("Martin", "Bernard", "Dubois", "Moreau", "Laurent",
			"Garcia", "Rossi", "Müller", "Janssens", "Silva", "Benali", "Fontaine", "Petit", "Leroy");

	private static final List<String> ADJECTIVES = List.of("Compact", "Pro", "Ultra", "Classic", "Eco", "Smart",
			"Wireless", "Premium", "Travel", "Studio", "Outdoor", "Mini");

	private static final List<String> NOUNS = List.of("Speaker", "Notebook", "Lens", "Kettle", "Backpack", "Lamp",
			"Chair", "Charger", "Drone", "Blender", "Tent", "Keyboard", "Headset", "Planter");

	private static final List<String> WORDS = List.of("durable", "lightweight", "designed", "for", "everyday", "use",
			"with", "a", "two-year", "warranty", "and", "recycled", "packaging", "the", "battery", "lasts", "up", "to",
			"hours", "on", "single", "charge", "materials", "tested", "in", "our", "lab", "compatible", "most",
			"accessories", "easy", "clean", "assemble", "ships", "within", "days");

	/** Seeded orders have 1 to this many lines. */
	static final int MAX_LINES_PER_ORDER = 6;

	private Dataset() {
	}

	record CustomerRow(long id, String email, String fullName, CustomerTier tier, String country) {
	}

	record ProductRow(long id, String sku, String name, String description, String category, BigDecimal unitPrice,
			int stock) {
	}

	record OrderRow(long id, long customerId, String country, List<LineRow> lines) {

		BigDecimal total() {
			return lines.stream()
				.map((line) -> line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		}

	}

	record LineRow(long productId, int quantity, BigDecimal unitPrice) {
	}

	static String sku(long productId) {
		return String.format(Locale.ROOT, "SKU-%07d", productId);
	}

	static CustomerRow customer(long id) {
		SplittableRandom random = new SplittableRandom(id * 31 + 1);
		// 60% bronze, 30% silver, 10% gold.
		int roll = random.nextInt(10);
		CustomerTier tier = (roll < 6) ? CustomerTier.BRONZE : (roll < 9) ? CustomerTier.SILVER : CustomerTier.GOLD;
		return new CustomerRow(id, "customer" + id + "@example.com",
				pick(random, FIRST_NAMES) + " " + pick(random, LAST_NAMES), tier, pick(random, VatRates.COUNTRIES));
	}

	/**
	 * Descriptions are 1,000 to 1,800 characters, the weight of a real product page: the full catalog
	 * does not fit comfortably in a 512 MiB heap.
	 */
	static ProductRow product(long id) {
		SplittableRandom random = new SplittableRandom(id * 31 + 2);
		String name = pick(random, ADJECTIVES) + " " + pick(random, NOUNS) + " " + (100 + random.nextInt(900));
		int length = 1_000 + random.nextInt(800);
		StringBuilder description = new StringBuilder(length + 16);
		while (description.length() < length) {
			description.append(pick(random, WORDS)).append(' ');
		}
		return new ProductRow(id, sku(id), name, description.toString().trim(), pick(random, Product.CATEGORIES),
				unitPrice(id), random.nextInt(500));
	}

	static BigDecimal unitPrice(long productId) {
		return BigDecimal.valueOf(199 + new SplittableRandom(productId * 31 + 3).nextInt(49_800), 2);
	}

	static OrderRow order(long id, int customers, int products) {
		SplittableRandom random = new SplittableRandom(id * 31 + 4);
		long customerId = 1 + random.nextInt(customers);
		int lineCount = 1 + random.nextInt(MAX_LINES_PER_ORDER);
		List<LineRow> lines = new ArrayList<>(lineCount);
		for (int i = 0; i < lineCount; i++) {
			long productId = 1 + random.nextInt(products);
			lines.add(new LineRow(productId, 1 + random.nextInt(5), unitPrice(productId)));
		}
		return new OrderRow(id, customerId, pick(random, VatRates.COUNTRIES), List.copyOf(lines));
	}

	private static <T> T pick(SplittableRandom random, List<T> values) {
		return values.get(random.nextInt(values.size()));
	}

}
