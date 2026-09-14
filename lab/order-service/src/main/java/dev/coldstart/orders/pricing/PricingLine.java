package dev.coldstart.orders.pricing;

import java.math.BigDecimal;

public record PricingLine(String sku, String category, BigDecimal unitPrice, int quantity) {
}
