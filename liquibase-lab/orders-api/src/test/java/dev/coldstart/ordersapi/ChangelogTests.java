package dev.coldstart.ordersapi;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Part 3, job 2: the whole changelog from an empty database, on the production engine, with Hibernate
 * checking every entity against the result. Needs Docker; skipped without it.
 */
@SpringBootTest(properties = {
	"spring.liquibase.enabled=true",
	"spring.liquibase.label-filter=",
	"spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers(disabledWithoutDocker = true)
class ChangelogTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

	@Autowired
	JdbcClient jdbc;

	@Test
	void changelogAppliesAndMatchesTheEntities() {
		// The context started: every changeset ran and Hibernate validated the schema.
		Long applied = this.jdbc.sql("SELECT count(*) FROM databasechangelog").query(Long.class).single();
		assertThat(applied).isPositive();
	}

}
