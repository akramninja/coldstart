package dev.coldstart.ordersapi;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The layout rules from part 3, checked without a database: parsing the changelog is enough.
 */
class ChangelogStructureTests {

	private static final String MASTER = "db/changelog/db.changelog-master.yaml";

	private static List<ChangeSet> changeSets;

	@BeforeAll
	static void parse() throws Exception {
		try (ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor()) {
			DatabaseChangeLog changeLog = ChangeLogParserFactory.getInstance()
				.getParser(MASTER, resources)
				.parse(MASTER, new ChangeLogParameters(), resources);
			changeSets = changeLog.getChangeSets();
		}
		changeSets.forEach(changeSet -> System.out.printf("%-70s labels=%s%n", changeSet.getFilePath() + "::"
				+ changeSet.getId(), changeSet.getLabels()));
	}

	@Test
	void everyPathIsRelativeToTheSearchPath() {
		// The path is part of a changeset's identity. Spring Boot, Maven and the migration image must agree.
		assertThat(changeSets).extracting(ChangeSet::getFilePath).allSatisfy(path -> assertThat(path)
			.startsWith("db/changelog/")
			.doesNotContain("classpath:")
			.doesNotContain("src/main/resources"));
	}

	@Test
	void oneChangeSetPerFileNamedAfterIt() {
		List<ChangeSet> changes = changeSets.stream()
			.filter(changeSet -> changeSet.getFilePath().startsWith("db/changelog/changes/"))
			.toList();
		Map<String, Long> perFile = changes.stream()
			.collect(Collectors.groupingBy(ChangeSet::getFilePath, Collectors.counting()));

		assertThat(perFile).allSatisfy((file, count) -> assertThat(count).as(file).isEqualTo(1));
		assertThat(changes).allSatisfy(changeSet -> assertThat(changeSet.getFilePath())
			.isEqualTo("db/changelog/changes/" + changeSet.getId() + ".yaml"));
	}

	@Test
	void changesRunInTimestampOrderAndRepeatablesLast() {
		List<String> paths = changeSets.stream().map(ChangeSet::getFilePath).toList();
		List<String> changes = paths.stream().filter(path -> path.contains("/changes/")).toList();

		assertThat(changes).isSorted();
		assertThat(paths.subList(0, changes.size())).isEqualTo(changes);
	}

	@Test
	void onlyRepeatablesRunOnChange() {
		assertThat(changeSets).allSatisfy(changeSet -> assertThat(changeSet.isRunOnChange())
			.as(changeSet.getId())
			.isEqualTo(changeSet.getFilePath().startsWith("db/changelog/repeatable/")));
	}

	@Test
	void everyChangeSetBelongsToARelease() {
		// The lab deploys releases with a label filter, so an unlabelled changeset would run in every release.
		assertThat(changeSets).allSatisfy(changeSet -> assertThat(changeSet.getLabels().getLabels())
			.as(changeSet.getId())
			.hasSize(1));
	}

}
