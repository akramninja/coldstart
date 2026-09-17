package dev.coldstart.ordersapi.report;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin report shipped in release 1.1. Without {@code orders_created_at_idx} it scans the whole
 * orders table; the index is the changeset that hangs the deploy in incident 1.
 */
@RestController
class DailyRevenueController {

	private final JdbcClient jdbc;

	DailyRevenueController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/api/reports/daily-revenue")
	DailyRevenue get(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
		Timestamp from = Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
		Timestamp to = Timestamp.from(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
		return this.jdbc
			.sql("SELECT count(*) AS orders, coalesce(sum(total), 0) AS revenue FROM orders WHERE created_at >= ? AND created_at < ?")
			.params(from, to)
			.query((rs, row) -> new DailyRevenue(day, rs.getLong("orders"), rs.getBigDecimal("revenue")))
			.single();
	}

	record DailyRevenue(LocalDate day, long orders, BigDecimal revenue) {
	}

}
