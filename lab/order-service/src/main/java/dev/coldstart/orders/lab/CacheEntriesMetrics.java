package dev.coldstart.orders.lab;

import java.util.Map;

import dev.coldstart.orders.catalog.ProductCatalog;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * {@code coldstart_cache_entries{cache="products"}}, whatever the cache manager. Spring Boot
 * publishes cache metrics for Caffeine but not for {@code ConcurrentMapCache}: the very cache
 * Default 5 needs to watch grow.
 */
@Component
class CacheEntriesMetrics implements MeterBinder {

	private final CacheManager cacheManager;

	CacheEntriesMetrics(CacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}

	@Override
	public void bindTo(MeterRegistry registry) {
		Gauge.builder("coldstart.cache.entries", this, (metrics) -> metrics.entries(ProductCatalog.CACHE))
			.description("Entries held by the cache, bounded or not")
			.tag("cache", ProductCatalog.CACHE)
			.register(registry);
	}

	private double entries(String name) {
		Cache cache = cacheManager.getCache(name);
		Object nativeCache = (cache != null) ? cache.getNativeCache() : null;
		if (nativeCache instanceof Map<?, ?> map) {
			return map.size();
		}
		if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeine) {
			return caffeine.estimatedSize();
		}
		return Double.NaN;
	}

}
