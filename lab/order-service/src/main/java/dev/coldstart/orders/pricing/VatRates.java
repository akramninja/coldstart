package dev.coldstart.orders.pricing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Standard VAT rates of the countries the service ships to.
 */
public final class VatRates {

	private static final Map<String, BigDecimal> RATES = Map.of("AT", new BigDecimal("0.20"), "BE",
			new BigDecimal("0.21"), "DE", new BigDecimal("0.19"), "ES", new BigDecimal("0.21"), "FR",
			new BigDecimal("0.20"), "IE", new BigDecimal("0.23"), "IT", new BigDecimal("0.22"), "LU",
			new BigDecimal("0.17"), "NL", new BigDecimal("0.21"), "PT", new BigDecimal("0.23"));

	public static final List<String> COUNTRIES = RATES.keySet().stream().sorted().toList();

	private VatRates() {
	}

	static BigDecimal of(String country) {
		BigDecimal rate = RATES.get(country);
		if (rate == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported country " + country);
		}
		return rate;
	}

}
