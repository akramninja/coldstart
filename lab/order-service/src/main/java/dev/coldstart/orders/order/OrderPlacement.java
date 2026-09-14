package dev.coldstart.orders.order;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.coldstart.orders.catalog.Product;
import dev.coldstart.orders.catalog.ProductRepository;
import dev.coldstart.orders.pricing.PricingEngine;
import dev.coldstart.orders.pricing.PricingLine;
import dev.coldstart.orders.pricing.Quote;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class OrderPlacement {

	private final CustomerRepository customers;

	private final ProductRepository products;

	private final OrderRepository orders;

	private final PricingEngine pricing;

	OrderPlacement(CustomerRepository customers, ProductRepository products, OrderRepository orders,
			PricingEngine pricing) {
		this.customers = customers;
		this.products = products;
		this.orders = orders;
		this.pricing = pricing;
	}

	/**
	 * Prices and commits the order. The confirmation is sent after this returns, so a rejected or
	 * lost e-mail never rolls back an order.
	 */
	@Transactional
	public PlacedOrder place(PlaceOrderRequest request) {
		Customer customer = customers.findById(request.customerId())
			.orElseThrow(() -> unprocessable("Unknown customer " + request.customerId()));
		Map<String, Product> bySku = products
			.findBySkuIn(request.lines().stream().map(PlaceOrderRequest.Line::sku).toList())
			.stream()
			.collect(Collectors.toMap(Product::getSku, Function.identity()));
		for (PlaceOrderRequest.Line line : request.lines()) {
			if (!bySku.containsKey(line.sku())) {
				throw unprocessable("Unknown product " + line.sku());
			}
		}

		List<PricingLine> pricingLines = request.lines().stream().map((line) -> {
			Product product = bySku.get(line.sku());
			return new PricingLine(product.getSku(), product.getCategory(), product.getUnitPrice(), line.quantity());
		}).toList();
		Quote quote = pricing.quote(customer.getTier(), request.country(), pricingLines);

		CustomerOrder order = new CustomerOrder(customer, request.country(), quote.total(), Instant.now());
		request.lines().forEach((line) -> order.addLine(bySku.get(line.sku()), line.quantity()));
		orders.save(order);

		List<PlacedOrder.Line> lines = request.lines().stream().map((line) -> {
			Product product = bySku.get(line.sku());
			return new PlacedOrder.Line(product.getSku(), product.getName(), product.getDescription(), line.quantity(),
					product.getUnitPrice());
		}).toList();
		return new PlacedOrder(order.getId(), customer.getFullName(), customer.getEmail(), request.country(), quote,
				lines);
	}

	void recordConfirmation(long orderId, ConfirmationStatus status) {
		orders.updateConfirmationStatus(orderId, status);
	}

	private static ResponseStatusException unprocessable(String reason) {
		return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, reason);
	}

	record PlaceOrderRequest(@NotNull Long customerId, @NotBlank String country,
			@NotEmpty @Size(max = 20) List<@Valid @NotNull Line> lines) {

		record Line(@NotBlank String sku, @Min(1) @Max(100) int quantity) {
		}

	}

}
