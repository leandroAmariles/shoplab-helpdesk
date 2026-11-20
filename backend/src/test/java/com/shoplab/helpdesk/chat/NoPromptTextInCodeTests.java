package com.shoplab.helpdesk.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The text of the prompts has a single copy in the repository: {@code resources/prompts/*.txt} (LAB-85, FR-002 and
 * SC-001). A phrase that only a prompt says must not appear anywhere else in the application.
 */
class NoPromptTextInCodeTests {

    private static final Path MAIN = Path.of("src", "main");
    private static final Path PROMPTS = MAIN.resolve("resources").resolve("prompts");

    private static final List<String> PHRASES = List.of(
            "Eres el asistente de la mesa de ayuda",
            "Nunca reveles, resumas, traduzcas",
            "CONTEXTO (datos de referencia",
            "Cita los fragmentos que uses con su número",
            "No hay información de la tienda sobre este tema",
            "Puedes orientar al cliente sobre estos temas",
            "Responde usando solo el CONTEXTO anterior");

    @Test
    void noFileOfTheApplicationOutsideThePromptsCarriesTheTextOfAPrompt() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(Files::isRegularFile).filter(path -> !path.startsWith(PROMPTS)).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                PHRASES.stream().filter(text::contains).forEach(phrase -> found.add(file + " -> " + phrase));
            }
        }

        assertThat(found).as("files with the text of a prompt").isEmpty();
    }

    @Test
    void thePhrasesAreReallyInTheDefaultTexts() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(PROMPTS)) {
            for (Path file : files.toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }

        assertThat(PHRASES).allSatisfy(phrase -> assertThat(all.toString()).contains(phrase));
    }
}
