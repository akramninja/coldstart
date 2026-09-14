package dev.coldstart.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * A small order service that runs on Spring Boot defaults. Each default of the article has a code
 * path that triggers it:
 * <ul>
 * <li>Defaults 1 and 5: {@code GET /api/products/{sku}}, a cached catalog lookup</li>
 * <li>Default 2: {@code POST /api/quotes}, pure CPU</li>
 * <li>Default 3: {@code GET /api/orders/{id}}, a database read, then a carrier API call</li>
 * <li>Default 4: {@code POST /api/orders}, an order, then an {@code @Async} confirmation e-mail</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching
@EnableAsync
public class OrderServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrderServiceApplication.class, args);
	}

}
