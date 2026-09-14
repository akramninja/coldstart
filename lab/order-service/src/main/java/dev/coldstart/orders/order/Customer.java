package dev.coldstart.orders.order;

import dev.coldstart.orders.pricing.CustomerTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "customers")
public class Customer {

	@Id
	private Long id;

	private String email;

	@Column(name = "full_name")
	private String fullName;

	@Enumerated(EnumType.STRING)
	private CustomerTier tier;

	private String country;

	protected Customer() {
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getFullName() {
		return fullName;
	}

	public CustomerTier getTier() {
		return tier;
	}

	public String getCountry() {
		return country;
	}

}
