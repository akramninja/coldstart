package dev.coldstart.orders.lab;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

import dev.coldstart.orders.LabProperties;
import dev.coldstart.orders.lab.Dataset.LineRow;
import dev.coldstart.orders.lab.Dataset.OrderRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes the dataset when the products table is empty: once per PostgreSQL volume, so every later
 * run starts on identical data.
 * <p>
 * It runs after Flyway and before the web server starts, so no load test can reach a half-seeded
 * database. Plain JDBC batches: seeding is lab plumbing, not something the articles measure.
 */
@Component
class DataSeeder implements SmartInitializingSingleton {

	private static final Logger logger = LoggerFactory.getLogger(DataSeeder.class);

	private static final int BATCH_SIZE = 2_000;

	/** Seeded orders are placed from here, seven minutes apart. */
	private static final Instant FIRST_ORDER = Instant.parse("2026-01-01T00:00:00Z");

	private final JdbcTemplate jdbc;

	private final LabProperties.Seed seed;

	DataSeeder(JdbcTemplate jdbc, LabProperties properties) {
		this.jdbc = jdbc;
		this.seed = properties.seed();
	}

	@Override
	public void afterSingletonsInstantiated() {
		Long existing = jdbc.queryForObject("select count(*) from products", Long.class);
		if (existing != null && existing > 0) {
			logger.info("Dataset already present ({} products), seeding skipped", existing);
			return;
		}
		long start = System.nanoTime();
		insert("insert into customers (id, email, full_name, tier, country) values (?, ?, ?, ?, ?)", seed.customers(),
				Dataset::customer, (statement, row) -> {
					statement.setLong(1, row.id());
					statement.setString(2, row.email());
					statement.setString(3, row.fullName());
					statement.setString(4, row.tier().name());
					statement.setString(5, row.country());
				});
		insert("insert into products (id, sku, name, description, category, unit_price, stock) values (?, ?, ?, ?, ?, ?, ?)",
				seed.products(), Dataset::product, (statement, row) -> {
					statement.setLong(1, row.id());
					statement.setString(2, row.sku());
					statement.setString(3, row.name());
					statement.setString(4, row.description());
					statement.setString(5, row.category());
					statement.setBigDecimal(6, row.unitPrice());
					statement.setInt(7, row.stock());
				});
		long lines = insertOrders();
		// Seeded ids are explicit: identity columns must continue after them.
		jdbc.execute("alter table orders alter column id restart with " + (seed.orders() + 1L));
		jdbc.execute("alter table order_lines alter column id restart with " + (lines + 1L));
		logger.info("Seeded {} customers, {} products, {} orders and {} order lines in {} s", seed.customers(),
				seed.products(), seed.orders(), lines, (System.nanoTime() - start) / 1_000_000_000L);
	}

	private long insertOrders() {
		long lineId = 0;
		List<OrderRow> orders = new ArrayList<>(BATCH_SIZE);
		List<Object[]> lines = new ArrayList<>(BATCH_SIZE * Dataset.MAX_LINES_PER_ORDER);
		for (long id = 1; id <= seed.orders(); id++) {
			OrderRow order = Dataset.order(id, seed.customers(), seed.products());
			orders.add(order);
			for (LineRow line : order.lines()) {
				lines.add(new Object[] { ++lineId, order.id(), line.productId(), line.quantity(), line.unitPrice() });
			}
			if (orders.size() == BATCH_SIZE || id == seed.orders()) {
				jdbc.batchUpdate("insert into orders (id, customer_id, status, confirmation_status, shipping_country, "
						+ "total, created_at) values (?, ?, 'PLACED', 'SENT', ?, ?, ?)", orders, orders.size(),
						(statement, row) -> {
							statement.setLong(1, row.id());
							statement.setLong(2, row.customerId());
							statement.setString(3, row.country());
							statement.setBigDecimal(4, row.total());
							statement.setObject(5,
									OffsetDateTime.ofInstant(FIRST_ORDER.plusSeconds(row.id() * 7 * 60), ZoneOffset.UTC));
						});
				jdbc.batchUpdate(
						"insert into order_lines (id, order_id, product_id, quantity, unit_price) values (?, ?, ?, ?, ?)",
						lines);
				orders.clear();
				lines.clear();
			}
		}
		return lineId;
	}

	private <T> void insert(String sql, int count, LongFunction<T> rows, RowBinder<T> binder) {
		List<T> batch = new ArrayList<>(BATCH_SIZE);
		for (long id = 1; id <= count; id++) {
			batch.add(rows.apply(id));
			if (batch.size() == BATCH_SIZE || id == count) {
				jdbc.batchUpdate(sql, batch, batch.size(), binder::bind);
				batch.clear();
			}
		}
	}

	@FunctionalInterface
	private interface RowBinder<T> {

		void bind(PreparedStatement statement, T row) throws SQLException;

	}

}
