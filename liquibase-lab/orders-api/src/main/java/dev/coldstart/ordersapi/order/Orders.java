package dev.coldstart.ordersapi.order;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Orders {

	private final OrderRepository orders;

	private final CustomerRepository customers;

	private final Clock clock;

	Orders(OrderRepository orders, CustomerRepository customers) {
		this.orders = orders;
		this.customers = customers;
		this.clock = Clock.systemUTC();
	}

	@Transactional(readOnly = true)
	public Optional<OrderView> find(long id) {
		return this.orders.findWithCustomerById(id).map(OrderView::from);
	}

	@Transactional
	public Optional<OrderView> place(long customerId, BigDecimal total) {
		return this.customers.findById(customerId)
			.map(customer -> this.orders.save(new CustomerOrder(customer, total, this.clock.instant())))
			.map(OrderView::from);
	}

}
