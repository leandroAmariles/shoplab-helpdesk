package com.shoplab.helpdesk.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The data-ownership lint passes on the real code and fails on each violation fixture (specs/LAB-105, S1-S3). */
class SchemaIsolationTests {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Path JAVA_SOURCES = Path.of("src/main/java/com/shoplab/helpdesk");
    private static final Path FIXTURES = Path.of("src/test/resources/schema/violations");
    private static final Set<String> MODULES = Set.of("chat", "retrieval", "orders", "catalog", "tickets");

    @ParameterizedTest
    @CsvSource({
        "cross-schema-fk.sql, catalog, S1",
        "qualified-foreign-name.sql, chat, S2",
        "foreign-search-path.sql, chat, S3"})
    void everyViolationFixtureIsReportedWithItsFileAndRule(String fixture, String module, String rule)
            throws IOException {
        String sql = Files.readString(FIXTURES.resolve(fixture));

        List<String> violations = SchemaIsolationRules.lint(fixture, module, MODULES, sql);

        assertThat(violations).isNotEmpty().allSatisfy(line -> assertThat(line).startsWith(fixture + ": "));
        assertThat(violations).anySatisfy(line -> assertThat(line).contains(": " + rule + ":"));
    }

    @Test
    void ownSchemaQualificationsAndCommentsAreNotViolations() {
        String sql = """
                -- orders.order is mentioned in a comment only
                CREATE TABLE catalog.product (id BIGINT PRIMARY KEY);
                CREATE TABLE catalog.price (product_id BIGINT REFERENCES catalog.product (id));
                /* tickets.ticket */
                """;

        assertThat(SchemaIsolationRules.lint("ok.sql", "catalog", MODULES, sql)).isEmpty();
    }

    @Test
    void theRealMigrationsAndSourcesRespectTheRules() {
        assertThat(SchemaIsolationRules.lintProject(MIGRATIONS, JAVA_SOURCES)).isEmpty();
    }

    @Test
    void everyMigrationFolderIsTheNameOfAModule() throws IOException {
        Set<String> folders = SchemaIsolationRules.moduleNames(MIGRATIONS);

        assertThat(folders).isEqualTo(MODULES);
        try (Stream<Path> packages = Files.list(JAVA_SOURCES)) {
            assertThat(packages.map(path -> path.getFileName().toString()).toList()).containsAll(folders);
        }
    }
}
