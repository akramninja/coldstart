package dev.coldstart.orders;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Default 3 on the running server: while {@code GET /api/orders/{id}} waits on the carrier API,
 * does the request still hold a database connection?
 */
class OpenInViewConnectionTests {

	/** Long enough to sample the pool well after the database read has finished. */
	private static final String SLOW_CARRIER = "coldstart.shipping.latency=900ms";

	static List<Integer> activeConnectionsDuringCarrierCall(String scenario, int port, DataSource dataSource)
			throws Exception {
		TestHttp app = new TestHttp(port);
		HikariDataSource pool = (HikariDataSource) dataSource;
		// Warm-up: JIT and Hibernate first-query costs stay out of the sampling window.
		assertThat(app.get("/api/orders/3").statusCode()).isEqualTo(200);

		CompletableFuture<HttpResponse<String>> response = app.getAsync("/api/orders/4");
		Thread.sleep(300);
		List<Integer> samples = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			samples.add(pool.getHikariPoolMXBean().getActiveConnections());
			Thread.sleep(50);
		}
		assertThat(response.get().statusCode()).isEqualTo(200);
		// Printed on purpose: the article quotes these lines.
		System.out.printf("%-42s active connections during carrier call: %s%n", scenario, samples);
		return samples;
	}

	@Nested
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "management.server.port=0", SLOW_CARRIER })
	@ActiveProfiles("test")
	class DefaultOpenInView {

		@LocalServerPort
		int port;

		@Autowired
		DataSource dataSource;

		@Test
		void holdsTheConnectionWhileWaitingOnTheCarrier() throws Exception {
			assertThat(activeConnectionsDuringCarrierCall("open-in-view on  (default)", port, dataSource)).containsOnly(1);
		}

	}

	@Nested
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "management.server.port=0", SLOW_CARRIER,
			"spring.jpa.open-in-view=false", "coldstart.orders.read-model=projection" })
	@ActiveProfiles("test")
	class OpenInViewOffWithProjection {

		@LocalServerPort
		int port;

		@Autowired
		DataSource dataSource;

		@Test
		void releasesTheConnectionBeforeTheCarrierCall() throws Exception {
			assertThat(activeConnectionsDuringCarrierCall("open-in-view off + fetch-join projection", port, dataSource))
				.containsOnly(0);
		}

	}

	@Nested
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = { "management.server.port=0", SLOW_CARRIER,
			"coldstart.orders.read-model=projection" })
	@ActiveProfiles("test")
	class OpenInViewOnWithProjection {

		@LocalServerPort
		int port;

		@Autowired
		DataSource dataSource;

		@Test
		void stillHoldsTheConnection() throws Exception {
			// The d3-osiv-control scenario: better queries alone don't give the connection back.
			assertThat(activeConnectionsDuringCarrierCall("open-in-view on  + fetch-join projection", port, dataSource))
				.containsOnly(1);
		}

	}

	@Nested
	@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
			properties = { "management.server.port=0", "spring.jpa.open-in-view=false" })
	@ActiveProfiles("test")
	class OpenInViewOffWithLazyEntities {

		@LocalServerPort
		int port;

		@Test
		void failsOnTheQueriesItWasHiding() throws Exception {
			// LazyInitializationException: the stack trace in the test output is expected.
			assertThat(new TestHttp(port).get("/api/orders/5").statusCode()).isEqualTo(500);
		}

	}

}
