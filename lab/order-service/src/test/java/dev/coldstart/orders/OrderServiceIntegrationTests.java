package dev.coldstart.orders;

import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.stream.Collectors;

import dev.coldstart.orders.lab.EffectiveDefaults;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

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
 * The application with nothing overridden: the "before" of every default except the cache, which
 * application.yaml bounds.
 */
// Metrics exporters are off in tests by default; the Prometheus endpoint is part of what is checked here.
@AutoConfigureMetrics
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles("test")
class OrderServiceIntegrationTests {

	static final String CART = """
			{"tier":"GOLD","country":"FR","lines":[
			  {"sku":"SKU-0000001","category":"audio","unitPrice":"149.90","quantity":3},
			  {"sku":"SKU-0000002","category":"books","unitPrice":"12.50","quantity":10}]}
			""";

	@LocalServerPort
	int port;

	@LocalManagementPort
	int managementPort;

	@Autowired
	ApplicationContext context;

	TestHttp app;

	TestHttp management;

	@BeforeEach
	void clients() {
		app = new TestHttp(port);
		management = new TestHttp(managementPort);
	}

	@Test
	void runsOnSpringBootDefaults() {
		EffectiveDefaults.Snapshot defaults = EffectiveDefaults.capture(context);

		assertThat(defaults.tomcatMaxThreads()).isEqualTo(200);
		assertThat(defaults.openInView()).isTrue();
		assertThat(defaults.orderReadModel()).isEqualTo(LabProperties.ReadModel.ENTITY);
		assertThat(defaults.hikariMaximumPoolSize()).isEqualTo(10);
		assertThat(defaults.taskExecutor()).isEqualTo("ThreadPoolTaskExecutor");
		assertThat(defaults.taskCorePoolSize()).isEqualTo(8);
		assertThat(defaults.taskMaxPoolSize()).isEqualTo(Integer.MAX_VALUE);
		assertThat(defaults.taskQueueCapacity()).isEqualTo(Integer.MAX_VALUE);
		assertThat(defaults.cacheManager()).isEqualTo("CaffeineCacheManager");
		assertThat(defaults.maxRamPercentage()).startsWith("25.0 (");
	}

	@Test
	void reportsSixteenDefaultsUnderItsTitle() {
		String report = EffectiveDefaults.capture(context).report();
		System.out.println(report);

		String[] lines = report.split("\n");
		int title = Arrays.asList(lines).indexOf(" DEFAULTS IN EFFECT (read from the running JVM and Spring beans)");
		assertThat(title).isPositive();
		assertThat(lines).hasSize(title + 18);
		assertThat(lines[title + 16]).startsWith(" 5 caffeineSpec ");
		assertThat(report).contains(" 2 tomcat.maxThreads        : 200\n", " 3 openInView               : true\n",
				" 3 hikari.maximumPoolSize   : 10\n", " 4 task core/max/queue      : 8 / 2147483647 / 2147483647\n");
	}

	@Test
	void servesTheDefaultsLiveOnTheInfoEndpoint() throws Exception {
		JsonNode defaults = TestHttp.json(management.get("/actuator/info")).get("defaults");

		assertThat(defaults.get("openInView").asBoolean()).isTrue();
		assertThat(defaults.get("orderReadModel").asString()).isEqualTo("ENTITY");
		assertThat(defaults.get("hikariMaximumPoolSize").asInt()).isEqualTo(10);
		assertThat(defaults.get("tomcatMaxThreads").asInt()).isEqualTo(200);
	}

	@Test
	void servesAProductFromTheCache() throws Exception {
		HttpResponse<String> response = app.get("/api/products/SKU-0000042");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(TestHttp.json(response).get("sku").asString()).isEqualTo("SKU-0000042");
		assertThat(app.get("/api/products/SKU-9999999").statusCode()).isEqualTo(404);
		// The endpoint shows the native cache: bounded Caffeine is a BoundedLocalCache.
		assertThat(management.get("/actuator/caches").body()).contains("caffeine.cache.BoundedLocalCache");
	}

	@Test
	void pricesACartWithoutTheDatabase() throws Exception {
		HttpResponse<String> response = app.post("/api/quotes", CART);

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode quote = TestHttp.json(response);
		assertThat(quote.get("subtotal").decimalValue()).isEqualByComparingTo("574.70");
		assertThat(quote.get("lines")).hasSize(2);
		assertThat(app.post("/api/quotes", CART.replace("\"FR\"", "\"US\"")).statusCode()).isEqualTo(400);
		assertThat(app.post("/api/quotes", "{\"tier\":\"GOLD\",\"country\":\"FR\",\"lines\":[]}").statusCode())
			.isEqualTo(400);
	}

	@Test
	void readsAnOrderThenQuotesShipping() throws Exception {
		HttpResponse<String> response = app.get("/api/orders/1");

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode order = TestHttp.json(response);
		assertThat(order.get("lines")).isNotEmpty();
		assertThat(order.get("customerName").asString()).isNotBlank();
		assertThat(order.get("shipping").get("carrier").asString()).isEqualTo("colis-express");
		assertThat(app.get("/api/orders/999999").statusCode()).isEqualTo(404);
	}

	@Test
	void placesAnOrderThenSendsItsConfirmationAsynchronously() throws Exception {
		HttpResponse<String> response = app.post("/api/orders", """
				{"customerId":7,"country":"DE","lines":[{"sku":"SKU-0000010","quantity":2},{"sku":"SKU-0000011","quantity":1}]}
				""");

		assertThat(response.statusCode()).isEqualTo(201);
		JsonNode placed = TestHttp.json(response);
		assertThat(placed.get("confirmation").asString()).isEqualTo("QUEUED");
		String status = "";
		for (int attempt = 0; attempt < 50 && !status.equals("SENT"); attempt++) {
			Thread.sleep(100);
			status = TestHttp.json(app.get("/api/orders/" + placed.get("id").asLong())).get("confirmationStatus").asString();
		}
		assertThat(status).isEqualTo("SENT");
		assertThat(app.post("/api/orders", "{\"customerId\":7,\"country\":\"DE\",\"lines\":[{\"sku\":\"NOPE\",\"quantity\":1}]}")
			.statusCode()).isEqualTo(422);
	}

	@Test
	void publishesTheSignalOfEachDefault() throws Exception {
		app.get("/api/products/SKU-0000001");
		app.get("/api/orders/2");

		String metrics = management.get("/actuator/prometheus").body();

		assertThat(metrics).contains("http_server_requests_seconds_bucket{");
		// Default 1 (jvm_gc_pause_seconds only appears after the first collection)
		assertThat(metrics).contains("jvm_memory_max_bytes{", "jvm_memory_used_bytes{", "jvm_gc_live_data_size_bytes{");
		// Default 2
		assertThat(metrics).contains("tomcat_threads_busy_threads{", "tomcat_threads_config_max_threads{");
		// Default 3
		assertThat(metrics).contains("hikaricp_connections_active{", "hikaricp_connections_pending{",
				"hikaricp_connections_acquire_seconds_bucket{");
		// Default 4
		assertThat(linesStartingWith(metrics, "executor_queued_tasks")).contains("name=\"applicationTaskExecutor\"");
		assertThat(metrics).contains("coldstart_confirmations_total{");
		// Default 5
		assertThat(metrics).contains("coldstart_cache_entries{", "cache_gets_total{");
		// Never on the application port
		assertThat(app.get("/actuator/prometheus").statusCode()).isEqualTo(404);
	}

	private static String linesStartingWith(String body, String prefix) {
		return Arrays.stream(body.split("\n")).filter((line) -> line.startsWith(prefix)).collect(Collectors.joining("\n"));
	}

}
