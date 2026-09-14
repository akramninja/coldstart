package dev.coldstart.orders.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import dev.coldstart.orders.catalog.Product;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * Associations are lazy, as they should be. Whether they can still be read after the repository
 * call returns depends on open-in-view: that is Default 3.
 */
@Entity
@Table(name = "orders")
public class CustomerOrder {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "customer_id")
	private Customer customer;

	private String status;

	@Enumerated(EnumType.STRING)
	@Column(name = "confirmation_status")
	private ConfirmationStatus confirmationStatus;

	@Column(name = "shipping_country")
	private String shippingCountry;

	private BigDecimal total;

	@Column(name = "created_at")
	private Instant createdAt;

	@OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
	@OrderBy("id")
	private List<OrderLine> lines = new ArrayList<>();

	protected CustomerOrder() {
	}

	CustomerOrder(Customer customer, String shippingCountry, BigDecimal total, Instant createdAt) {
		this.customer = customer;
		this.status = "PLACED";
		this.confirmationStatus = ConfirmationStatus.QUEUED;
		this.shippingCountry = shippingCountry;
		this.total = total;
		this.createdAt = createdAt;
	}

	void addLine(Product product, int quantity) {
		lines.add(new OrderLine(this, product, quantity, product.getUnitPrice()));
	}

	public Long getId() {
		return id;
	}

	public Customer getCustomer() {
		return customer;
	}

	public String getStatus() {
		return status;
	}

	public ConfirmationStatus getConfirmationStatus() {
		return confirmationStatus;
	}

	public String getShippingCountry() {
		return shippingCountry;
	}

	public BigDecimal getTotal() {
		return total;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public List<OrderLine> getLines() {
		return lines;
	}

}
