package dev.coldstart.orders.order;

import dev.coldstart.orders.LabProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The two ways to read an order that Default 3 compares, selected with
 * {@code coldstart.orders.read-model}.
 */
interface OrderReader {

	OrderView read(long id);

	private static ResponseStatusException notFound(long id) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown order " + id);
	}

	/**
	 * The common code: load the entity, map it in the web layer. Customer, lines and products load
	 * lazily, one query at a time, after the repository's transaction has ended. With
	 * {@code spring.jpa.open-in-view=false}, this throws {@code LazyInitializationException}.
	 */
	class EntityReader implements OrderReader {

		private final OrderRepository orders;

		EntityReader(OrderRepository orders) {
			this.orders = orders;
		}

		@Override
		public OrderView read(long id) {
			return orders.findById(id).map(OrderView::from).orElseThrow(() -> notFound(id));
		}

	}

	/**
	 * The fix: load what the response needs in one query and map it inside a read-only
	 * transaction. With open-in-view off, the connection is back in the pool when this returns.
	 */
	class ProjectionReader implements OrderReader {

		private final OrderRepository orders;

		ProjectionReader(OrderRepository orders) {
			this.orders = orders;
		}

		@Override
		@Transactional(readOnly = true)
		public OrderView read(long id) {
			return orders.findWithDetailsById(id).map(OrderView::from).orElseThrow(() -> notFound(id));
		}

	}

	@Configuration(proxyBeanMethods = false)
	class ReaderConfiguration {

		@Bean
		OrderReader orderReader(LabProperties properties, OrderRepository orders) {
			return switch (properties.orders().readModel()) {
				case ENTITY -> new EntityReader(orders);
				case PROJECTION -> new ProjectionReader(orders);
			};
		}

	}

}
