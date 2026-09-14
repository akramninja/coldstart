package dev.coldstart.orders.order;

import java.math.BigDecimal;
import java.net.URI;

import dev.coldstart.orders.order.OrderPlacement.PlaceOrderRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
class OrderController {

	private final OrderReader reader;

	private final ShippingQuotes shippingQuotes;

	private final OrderPlacement placement;

	private final OrderConfirmations confirmations;

	OrderController(OrderReader reader, ShippingQuotes shippingQuotes, OrderPlacement placement,
			OrderConfirmations confirmations) {
		this.reader = reader;
		this.shippingQuotes = shippingQuotes;
		this.placement = placement;
		this.confirmations = confirmations;
	}

	/**
	 * Default 3: the carrier call comes after the order is read. Whether the request still holds a
	 * database connection during that call depends only on open-in-view.
	 */
	@GetMapping("/{id}")
	OrderView get(@PathVariable long id) {
		OrderView order = reader.read(id);                                                     // database
		return order.withShipping(shippingQuotes.quote(order.country(), order.itemCount()));  // 100 ms HTTP call
	}

	/**
	 * Default 4: the order is committed, then its confirmation goes to the async executor.
	 */
	@PostMapping
	ResponseEntity<PlacedOrderResponse> place(@Valid @RequestBody PlaceOrderRequest request) {
		PlacedOrder order = placement.place(request);
		ConfirmationStatus confirmation = confirmations.dispatch(order);
		return ResponseEntity.created(URI.create("/api/orders/" + order.id()))
			.body(new PlacedOrderResponse(order.id(), order.quote().total(), confirmation));
	}

	record PlacedOrderResponse(long id, BigDecimal total, ConfirmationStatus confirmation) {
	}

}
