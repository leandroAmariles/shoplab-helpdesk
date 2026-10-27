package com.shoplab.helpdesk.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Static lint of the data-ownership rules (specs/LAB-105, contracts/schema-isolation.md): a module's SQL only
 * touches its own schema.
 *
 * <ul>
 *   <li>S1: no {@code REFERENCES} to a schema other than the module's own;</li>
 *   <li>S2: no name qualified with another module's schema;</li>
 *   <li>S3: no {@code SET search_path}, {@code CREATE SCHEMA} or {@code SET SCHEMA}.</li>
 * </ul>
 */
final class SchemaIsolationRules {

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");
    private static final Pattern REFERENCES = Pattern.compile(
            "\\bREFERENCES\\s+\"?([a-z_][a-z0-9_]*)\"?\\s*\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEARCH_PATH = Pattern.compile("\\bSET\\s+(?:LOCAL\\s+)?search_path\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_SCHEMA = Pattern.compile("\\bCREATE\\s+SCHEMA\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SET_SCHEMA = Pattern.compile("\\bSET\\s+SCHEMA\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);

    private SchemaIsolationRules() {
    }

    /** Violations of {@code sql}, written by {@code module}, as {@code "<source>: <rule>: <detail>"} lines. */
    static List<String> lint(String source, String module, Set<String> allModules, String sql) {
        String code = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(sql).replaceAll(" ")).replaceAll(" ");
        List<String> violations = new ArrayList<>();

        Matcher references = REFERENCES.matcher(code);
        while (references.find()) {
            if (!references.group(1).equals(module)) {
                violations.add(source + ": S1: foreign key into schema '" + references.group(1) + "'");
            }
        }
        for (String other : allModules) {
            if (!other.equals(module)
                    && Pattern.compile("(?<![\\w.])\"?" + other + "\"?\\s*\\.\\s*\"?\\w").matcher(code).find()) {
                violations.add(source + ": S2: name qualified with the schema of module '" + other + "'");
            }
        }
        if (SEARCH_PATH.matcher(code).find()) {
            violations.add(source + ": S3: SET search_path");
        }
        if (CREATE_SCHEMA.matcher(code).find()) {
            violations.add(source + ": S3: CREATE SCHEMA");
        }
        if (SET_SCHEMA.matcher(code).find()) {
            violations.add(source + ": S3: SET SCHEMA");
        }
        return violations;
    }

    /** Lints the migrations of every module and the SQL it embeds as text blocks in its Java sources. */
    static List<String> lintProject(Path migrationRoot, Path javaRoot) {
        Set<String> modules = moduleNames(migrationRoot);
        List<String> violations = new ArrayList<>();
        for (String module : modules) {
            for (Path script : files(migrationRoot.resolve(module), ".sql")) {
                violations.addAll(lint(script.toString(), module, modules, read(script)));
            }
            for (Path source : files(javaRoot.resolve(module), ".java")) {
                Matcher blocks = TEXT_BLOCK.matcher(read(source));
                while (blocks.find()) {
                    violations.addAll(lint(source.toString(), module, modules, blocks.group(1)));
                }
            }
        }
        return violations;
    }

    static Set<String> moduleNames(Path migrationRoot) {
        try (Stream<Path> directories = Files.list(migrationRoot)) {
            return directories.filter(Files::isDirectory)
                    .map(directory -> directory.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> files(Path directory, String extension) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(path -> path.toString().endsWith(extension)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
