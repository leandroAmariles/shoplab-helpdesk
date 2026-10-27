package com.shoplab.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTests {

    private static final Set<String> EXPECTED_MODULES =
            Set.of("chat", "retrieval", "orders", "catalog", "tickets", "shared");
    private static final Path DOCS_OUTPUT = Path.of("..", "docs", "modulos");

    private final ApplicationModules modules = ApplicationModules.of(ShopLabHelpdeskApplication.class);

    @Test
    void detectsExactlyTheExpectedModules() {
        Set<String> detected = modules.stream()
                .map(module -> module.getIdentifier().toString())
                .collect(Collectors.toSet());

        assertThat(detected).isEqualTo(EXPECTED_MODULES);
    }

    @Test
    void thereIsNoTechnicalToolsModule() {
        assertThat(modules.stream().map(module -> module.getIdentifier().toString())).doesNotContain("tools");
    }

    @Test
    void verifiesModuleStructure() {
        modules.verify();
    }

    /**
     * Regenerates {@code docs/modulos} from the code (specs/LAB-105, FR-010). The folder is generated, never edited by
     * hand; CI fails when committing the code without the regenerated documentation.
     */
    @Test
    void writesTheModuleDocumentation() throws IOException {
        new Documenter(modules, Documenter.Options.defaults().withOutputFolder(DOCS_OUTPUT.toString()))
                .writeDocumentation();
        sortRelationLines();

        try (Stream<Path> files = Files.list(DOCS_OUTPUT)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .anyMatch(name -> name.endsWith(".puml"))
                    .contains("module-chat.adoc", "module-retrieval.adoc", "module-orders.adoc",
                            "module-catalog.adoc", "module-tickets.adoc");
        }
    }

    /**
     * The Documenter lists the relations of a diagram in an order that can change between runs; sorting each block of
     * {@code Rel(...)} lines keeps the generated files identical so the drift check in CI does not flap.
     */
    private static void sortRelationLines() throws IOException {
        try (Stream<Path> files = Files.list(DOCS_OUTPUT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".puml")).toList()) {
                List<String> sorted = new ArrayList<>();
                List<String> block = new ArrayList<>();
                for (String line : Files.readAllLines(file)) {
                    if (line.startsWith("Rel(")) {
                        block.add(line);
                    } else {
                        block.stream().sorted().forEach(sorted::add);
                        block.clear();
                        sorted.add(line);
                    }
                }
                block.stream().sorted().forEach(sorted::add);
                Files.writeString(file, String.join("\n", sorted) + "\n");
            }
        }
    }
}
