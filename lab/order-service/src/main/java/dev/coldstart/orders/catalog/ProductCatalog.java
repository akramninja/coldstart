package dev.coldstart.orders.catalog;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Default 5. The annotation is identical before and after: whether this cache ever evicts depends
 * only on the cache manager Spring Boot configured. Without a cache provider, that is a
 * {@code ConcurrentMapCacheManager}: no size limit, no expiry.
 */
@Service
public class ProductCatalog {

	public static final String CACHE = "products";

	private final ProductRepository products;

	ProductCatalog(ProductRepository products) {
		this.products = products;
	}

	@Cacheable(CACHE)
	public ProductView find(String sku) {
		return products.findBySku(sku)
			.map(ProductView::from)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown product " + sku));
	}

}
