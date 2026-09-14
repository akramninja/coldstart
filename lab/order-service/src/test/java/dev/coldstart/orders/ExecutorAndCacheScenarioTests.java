package dev.coldstart.orders;

import java.util.ArrayList;
import java.util.List;

import dev.coldstart.orders.lab.EffectiveDefaults;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defaults 4 and 5, each configured the way its scenario file configures it.
 */
class ExecutorAndCacheScenarioTests {

	@Nested
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
			properties = { "management.server.port=0", "coldstart.confirmation.provider-latency=2s",
					"spring.task.execution.pool.core-size=1", "spring.task.execution.pool.max-size=1",
					"spring.task.execution.pool.queue-capacity=1" })
	@ActiveProfiles("test")
	class BoundedExecutor {

		@LocalServerPort
		int port;

		@Test
		void defersConfirmationsInsteadOfQueueingThemInTheHeap() throws Exception {
			TestHttp app = new TestHttp(port);
			List<String> outcomes = new ArrayList<>();
			for (int customer = 1; customer <= 3; customer++) {
				String order = "{\"customerId\":%d,\"country\":\"FR\",\"lines\":[{\"sku\":\"SKU-0000020\",\"quantity\":1}]}"
					.formatted(customer);
				outcomes.add(TestHttp.json(app.post("/api/orders", order)).get("confirmation").asString());
			}

			// One sending on the only thread, one waiting in the queue of 1, one rejected.
			assertThat(outcomes).containsExactly("QUEUED", "QUEUED", "DEFERRED");
		}

	}

	@Nested
	@AutoConfigureMetrics
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
			properties = { "management.server.port=0", "spring.cache.type=simple" })
	@ActiveProfiles("test")
	class SimpleCache {

		@LocalServerPort
		int port;

		@LocalManagementPort
		int managementPort;

		@Autowired
		ApplicationContext context;

		@Test
		void keepsEveryProductItHasEverServed() throws Exception {
			TestHttp app = new TestHttp(port);
			for (int id = 1; id <= 300; id++) {
				assertThat(app.get("/api/products/SKU-%07d".formatted(id)).statusCode()).isEqualTo(200);
			}

			TestHttp management = new TestHttp(managementPort);
			assertThat(EffectiveDefaults.capture(context).cacheManager()).isEqualTo("ConcurrentMapCacheManager");
			// The endpoint shows the native cache: a plain map, the article's check.
			String caches = management.get("/actuator/caches").body();
			System.out.println(caches);
			assertThat(caches).contains("\"target\":\"java.util.concurrent.ConcurrentHashMap\"");
			assertThat(management.get("/actuator/prometheus").body())
				.contains("coldstart_cache_entries{application=\"order-service\",cache=\"products\"} 300.0");
		}

	}

	@Nested
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
			properties = { "management.server.port=0", "spring.cache.caffeine.spec=" })
	@ActiveProfiles("test")
	class CaffeineWithoutSpec {

		@LocalServerPort
		int port;

		@LocalManagementPort
		int managementPort;

		@Autowired
		ApplicationContext context;

		@Test
		void isUnboundedToo() throws Exception {
			new TestHttp(port).get("/api/products/SKU-0000001");

			assertThat(EffectiveDefaults.capture(context).cacheManager()).isEqualTo("CaffeineCacheManager");
			assertThat(new TestHttp(managementPort).get("/actuator/caches").body())
				.contains("caffeine.cache.UnboundedLocalCache");
		}

	}

}
