package dev.coldstart.orders.pricing;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prices a cart snapshot sent by the cart service. Prices and categories come with the request, so
 * a quote never touches the database: it is the service's pure CPU path.
 */
@RestController
class QuoteController {

	private final PricingEngine pricing;

	QuoteController(PricingEngine pricing) {
		this.pricing = pricing;
	}

	@PostMapping("/api/quotes")
	Quote quote(@Valid @RequestBody QuoteRequest request) {
		List<PricingLine> lines = request.lines()
			.stream()
			.map((line) -> new PricingLine(line.sku(), line.category(), line.unitPrice(), line.quantity()))
			.toList();
		return pricing.quote(request.tier(), request.country(), lines);
	}

	record QuoteRequest(@NotNull CustomerTier tier, @NotBlank String country,
			@NotEmpty @Size(max = 50) List<@Valid @NotNull Line> lines) {

		record Line(@NotBlank String sku, @NotBlank String category, @NotNull @DecimalMin("0.01") BigDecimal unitPrice,
				@Min(1) @Max(1000) int quantity) {
		}

	}

}
