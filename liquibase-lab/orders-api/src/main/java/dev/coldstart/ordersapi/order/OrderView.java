package dev.coldstart.ordersapi.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderView(long id, long customerId, String customerEmail, String status, BigDecimal total,
		Instant createdAt) {

	static OrderView from(CustomerOrder order) {
		Customer customer = order.getCustomer();
		return new OrderView(order.getId(), customer.getId(), customer.getEmail(), order.getStatus(), order.getTotal(),
				order.getCreatedAt());
	}

}
