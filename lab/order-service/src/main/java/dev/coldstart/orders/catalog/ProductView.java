package dev.coldstart.orders.catalog;

import java.math.BigDecimal;

/**
 * What the product endpoint returns and what the {@code products} cache holds: an immutable copy,
 * so no Hibernate state stays reachable from the cache.
 */
public record ProductView(String sku, String name, String description, String category, BigDecimal unitPrice,
		int stock) {

	static ProductView from(Product product) {
		return new ProductView(product.getSku(), product.getName(), product.getDescription(), product.getCategory(),
				product.getUnitPrice(), product.getStock());
	}

}
