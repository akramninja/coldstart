package dev.coldstart.orders.catalog;

import java.math.BigDecimal;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
public class Product {

	public static final List<String> CATEGORIES = List.of("audio", "books", "cameras", "computers", "garden", "home",
			"kitchen", "office", "phones", "sports", "toys", "travel");

	@Id
	private Long id;

	private String sku;

	private String name;

	private String description;

	private String category;

	@Column(name = "unit_price")
	private BigDecimal unitPrice;

	private int stock;

	protected Product() {
	}

	public Long getId() {
		return id;
	}

	public String getSku() {
		return sku;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public String getCategory() {
		return category;
	}

	public BigDecimal getUnitPrice() {
		return unitPrice;
	}

	public int getStock() {
		return stock;
	}

}
