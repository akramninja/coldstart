package dev.coldstart.orders.pricing;

import java.math.BigDecimal;
import java.util.List;

public record Quote(List<Line> lines, BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal total) {

	/**
	 * @param promotionId the best promotion for this line, or {@code null} when none applies
	 */
	public record Line(String sku, int quantity, BigDecimal unitPrice, Integer promotionId, BigDecimal discount,
			BigDecimal amount) {
	}

}
