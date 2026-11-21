package com.shoplab.helpdesk.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The evaluation tool never talks to PostgreSQL: it hands each run to the eval module of the backend, the only owner of
 * the schema (specs/LAB-31, ADR-0028, research.md R1). Its documentation and tests may show how to query the history,
 * so only the code and the dependencies of the tool are looked at.
 */
class EvalHasNoDatabaseCredentialsTests {

    private static final Path EVAL = Path.of("../eval");
    private static final List<Path> CODE = List.of(EVAL.resolve("src"), EVAL.resolve("pyproject.toml"),
            EVAL.resolve("uv.lock"));
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".venv", "__pycache__", ".pytest_cache",
            ".ruff_cache", "results");
    private static final List<String> FORBIDDEN = List.of("DATABASE_URL", "POSTGRES_", "PGHOST", "PGPASSWORD",
            "SPRING_DATASOURCE", "jdbc:", "postgres://", "postgresql://", "psycopg", "asyncpg");

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

    @ParameterizedTest
    @ValueSource(strings = {"DATABASE_URL=x", "host=PGHOST", "url = 'jdbc:postgresql://db'", "import psycopg",
            "postgres://user@host/db", "SPRING_DATASOURCE_URL", "os.environ['POSTGRES_PASSWORD']", "asyncpg>=0.29"})
    void everyKindOfCredentialIsDetected(String text) {
        assertThat(violationsIn("synthetic", text)).isNotEmpty();
    }

    @Test
    void aSourceWithoutCredentialsHasNoViolation() {
        assertThat(violationsIn("synthetic", "base_url = 'http://localhost:8080'\ntoken = os.environ['SHOPLAB_EVAL_TOKEN']"))
                .isEmpty();
    }

    @Test
    void theEvaluationToolHasNoDatabaseCredentialsInItsCodeNorItsDependencies() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path root : CODE) {
            if (Files.isRegularFile(root)) {
                scan(root, violations);
            } else if (Files.isDirectory(root)) {
                List<Path> files;
                try (Stream<Path> walk = Files.walk(root)) {
                    files = walk.filter(Files::isRegularFile).filter(EvalHasNoDatabaseCredentialsTests::isSource)
                            .toList();
                }
                for (Path file : files) {
                    scan(file, violations);
                }
            }
        }

        assertThat(violations).as("credentials of a database in eval/").isEmpty();
    }

    private static boolean isSource(Path path) {
        for (Path part : path) {
            if (SKIPPED_DIRECTORIES.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }

    private static void scan(Path path, List<String> violations) throws IOException {
        try {
            violations.addAll(violationsIn(path.toString().replace('\\', '/'), Files.readString(path)));
        } catch (MalformedInputException binary) {
            // a compiled file is not text: nothing to read
        }
    }
}
