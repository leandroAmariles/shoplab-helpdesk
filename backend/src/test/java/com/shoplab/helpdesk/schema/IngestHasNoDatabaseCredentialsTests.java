package com.shoplab.helpdesk.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The ingest service never talks to PostgreSQL: it hands its output to the retrieval module, the only owner of the
 * schema (specs/LAB-19, contracts/ingest-no-database-credentials.md).
 */
class IngestHasNoDatabaseCredentialsTests {

    private static final Path INGEST = Path.of("../ingest");
    private static final Path COMPOSE = Path.of("../infra/docker-compose.yml");
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".venv", "__pycache__", "node_modules",
            ".pytest_cache", ".ruff_cache");
    private static final List<String> FORBIDDEN = List.of("DATABASE_URL", "POSTGRES_", "PGHOST", "PGPASSWORD",
            "SPRING_DATASOURCE", "DB_HOST", "DB_PASSWORD", "DB_USER", "jdbc:", "postgres://", "postgresql://");
    private static final Pattern INGEST_SERVICE = Pattern.compile("^ {2}ingest:\\s*$");
    private static final Pattern NEXT_SERVICE = Pattern.compile("^ {2}\\S");
    private static final Pattern POSTGRES_WORD = Pattern.compile("\\bpostgres\\b", Pattern.CASE_INSENSITIVE);

    /** Violations of {@code content}, as {@code "<source>: <pattern>"} lines. */
    static List<String> violationsIn(String source, String content) {
        String lower = content.toLowerCase();
        List<String> violations = new ArrayList<>();
        for (String pattern : FORBIDDEN) {
            if (lower.contains(pattern.toLowerCase())) {
                violations.add(source + ": " + pattern);
            }
        }
        return violations;
    }

    /** The lines of the {@code ingest} service of a compose file, or an empty text when it has none. */
    static String ingestServiceBlock(String compose) {
        StringBuilder block = new StringBuilder();
        boolean inside = false;
        for (String line : compose.split("\\R")) {
            if (inside && NEXT_SERVICE.matcher(line).find()) {
                break;
            }
            if (!inside && INGEST_SERVICE.matcher(line).find()) {
                inside = true;
            }
            if (inside) {
                block.append(line).append('\n');
            }
        }
        return block.toString();
    }

    static List<String> composeViolations(String source, String compose) {
        String block = ingestServiceBlock(compose);
        List<String> violations = new ArrayList<>(violationsIn(source, block));
        if (POSTGRES_WORD.matcher(block).find()) {
            violations.add(source + ": reference to the postgres service");
        }
        return violations;
    }

    @ParameterizedTest
    @ValueSource(strings = {"DATABASE_URL", "POSTGRES_", "PGHOST", "PGPASSWORD", "SPRING_DATASOURCE", "DB_HOST",
        "DB_PASSWORD", "DB_USER", "jdbc:", "postgres://", "postgresql://"})
    void everyForbiddenPatternIsDetected(String pattern) {
        String dockerfile = "FROM python:3.12-slim\nENV " + pattern.toLowerCase() + "x=1\n";

        assertThat(violationsIn("Dockerfile", dockerfile)).contains("Dockerfile: " + pattern);
    }

    @Test
    void aConfigurationWithoutDatabaseSettingsHasNoViolations() {
        String settings = "OLLAMA_BASE_URL=http://ollama:11434\nRETRIEVAL_URL=http://backend:8080\n";

        assertThat(violationsIn(".env", settings)).isEmpty();
    }

    @Test
    void anIngestServiceWithDatabaseSettingsIsReported() {
        String compose = """
                services:
                  backend:
                    environment:
                      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/shoplab
                  ingest:
                    build: ../ingest
                    environment:
                      POSTGRES_PASSWORD: change-me
                    depends_on:
                      - postgres
                  frontend:
                    image: nginx
                """;

        assertThat(composeViolations("compose", compose)).contains("compose: POSTGRES_",
                "compose: reference to the postgres service");
    }

    @Test
    void theDatabaseSettingsOfOtherServicesAreNotAttributedToIngest() {
        String compose = """
                services:
                  backend:
                    environment:
                      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/shoplab
                  ingest:
                    build: ../ingest
                    environment:
                      OLLAMA_BASE_URL: http://ollama:11434
                  frontend:
                    depends_on:
                      - postgres
                """;

        assertThat(composeViolations("compose", compose)).isEmpty();
    }

    @Test
    void theRealIngestConfigurationHasNoDatabaseCredentials() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(INGEST)) {
            for (Path file : files.filter(Files::isRegularFile).filter(IngestHasNoDatabaseCredentialsTests::isScanned)
                    .sorted().toList()) {
                try {
                    violations.addAll(violationsIn(file.toString(), Files.readString(file)));
                } catch (MalformedInputException binary) {
                    // not a text file: it cannot hold a connection string
                }
            }
        }
        if (Files.isRegularFile(COMPOSE)) {
            violations.addAll(composeViolations(COMPOSE.toString(), Files.readString(COMPOSE)));
        }

        assertThat(violations).isEmpty();
    }

    private static boolean isScanned(Path file) {
        for (Path part : INGEST.relativize(file)) {
            if (SKIPPED_DIRECTORIES.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }
}
