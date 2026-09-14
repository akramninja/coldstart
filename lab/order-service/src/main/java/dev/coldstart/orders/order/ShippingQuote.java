package dev.coldstart.orders.order;

import java.math.BigDecimal;

public record ShippingQuote(String carrier, BigDecimal price, int etaDays) {
}
