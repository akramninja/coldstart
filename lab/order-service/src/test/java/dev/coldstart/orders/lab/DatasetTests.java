package dev.coldstart.orders.lab;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetTests {

	@Test
	void everyValueIsAFunctionOfItsId() {
		assertThat(Dataset.product(42)).isEqualTo(Dataset.product(42));
		assertThat(Dataset.customer(7)).isEqualTo(Dataset.customer(7));
		assertThat(Dataset.order(99, 1000, 5000)).isEqualTo(Dataset.order(99, 1000, 5000));
		assertThat(Dataset.product(42)).isNotEqualTo(Dataset.product(43));
	}

	@Test
	void skusMatchTheLoadGenerator() {
		assertThat(Dataset.product(42).sku()).isEqualTo("SKU-0000042");
	}

	@Test
	void orderLinesUseCatalogPricesAndAddUpToTheTotal() {
		Dataset.OrderRow order = Dataset.order(123, 1000, 5000);

		BigDecimal sum = BigDecimal.ZERO;
		for (Dataset.LineRow line : order.lines()) {
			assertThat(line.unitPrice()).isEqualTo(Dataset.product(line.productId()).unitPrice());
			sum = sum.add(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
		}
		assertThat(order.total()).isEqualByComparingTo(sum);
		assertThat(order.lines()).hasSizeBetween(1, Dataset.MAX_LINES_PER_ORDER);
		assertThat(order.customerId()).isBetween(1L, 1000L);
	}

	@Test
	void descriptionsWeighWhatARealProductPageWeighs() {
		for (long id = 1; id <= 200; id++) {
			assertThat(Dataset.product(id).description().length()).isBetween(990, 1_820);
		}
	}

}
