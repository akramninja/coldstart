package dev.coldstart.ordersapi.order;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Hibernate lists every mapped column in every SELECT and INSERT on this entity. That's why renaming
 * {@code total} in one release breaks every order request of the previous release (part 2).
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

	private BigDecimal total;

	@Column(name = "created_at")
	private Instant createdAt;

	protected CustomerOrder() {
	}

	CustomerOrder(Customer customer, BigDecimal total, Instant createdAt) {
		this.customer = customer;
		this.status = "PLACED";
		this.total = total;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return this.id;
	}

	public Customer getCustomer() {
		return this.customer;
	}

	public String getStatus() {
		return this.status;
	}

	public BigDecimal getTotal() {
		return this.total;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

}
