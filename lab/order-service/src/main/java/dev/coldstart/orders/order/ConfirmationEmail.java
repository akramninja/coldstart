package dev.coldstart.orders.order;

/**
 * The rendered confirmation, about 15 KB for a typical order. It is built on the request thread and
 * handed to the async executor, so every task waiting in the queue keeps one reachable: that is
 * what an unbounded queue piles up in the heap (Default 4).
 */
record ConfirmationEmail(long orderId, String to, String subject, String html) {

	/** Withdrawal right, terms and privacy notice: the bulk of a real order confirmation. */
	private static final String LEGAL_NOTICE = ("<p>You may withdraw from this contract within 14 days without "
			+ "giving any reason. The withdrawal period expires 14 days after the day on which you acquire physical "
			+ "possession of the goods. Personal data is processed to perform the contract and kept for the period "
			+ "required by accounting law. Full terms of sale are available on request.</p>")
		.repeat(35);

	static ConfirmationEmail render(PlacedOrder order) {
		StringBuilder html = new StringBuilder(16_384);
		html.append("<html><body><h1>Thank you for your order, ").append(order.customerName()).append("</h1>");
		html.append("<p>Order #").append(order.id()).append(", shipping to ").append(order.country()).append("</p>");
		html.append("<table>");
		for (PlacedOrder.Line line : order.lines()) {
			html.append("<tr><td>").append(line.name());
			html.append("<br><small>").append(line.description()).append("</small></td>");
			html.append("<td>").append(line.quantity()).append("</td>");
			html.append("<td>").append(line.unitPrice()).append("</td></tr>");
		}
		html.append("</table>");
		html.append("<p>Subtotal ").append(order.quote().subtotal());
		html.append(", discount ").append(order.quote().discount());
		html.append(", VAT ").append(order.quote().tax());
		html.append(", total ").append(order.quote().total()).append("</p>");
		html.append(LEGAL_NOTICE).append("</body></html>");
		return new ConfirmationEmail(order.id(), order.customerEmail(), "Your order #" + order.id(), html.toString());
	}

}
