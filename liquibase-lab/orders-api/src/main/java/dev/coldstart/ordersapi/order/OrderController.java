package dev.coldstart.ordersapi.order;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/orders")
class OrderController {

	private final Orders orders;

	OrderController(Orders orders) {
		this.orders = orders;
	}

	@GetMapping("/{id}")
	OrderView get(@PathVariable long id) {
		return this.orders.find(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No order " + id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	OrderView place(@RequestBody PlaceOrder request) {
		return this.orders.place(request.customerId(), request.total())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No customer " + request.customerId()));
	}

	record PlaceOrder(long customerId, BigDecimal total) {
	}

}
