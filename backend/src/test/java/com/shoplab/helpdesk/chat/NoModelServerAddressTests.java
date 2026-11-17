package com.shoplab.helpdesk.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The backend knows the model gateway and nothing else (LAB-84, FR-001): no address, port, property or credential of a
 * particular model server is left in its configuration or its code.
 */
class NoModelServerAddressTests {

    private static final Pattern MODEL_SERVER = Pattern.compile(
            "11434|spring\\.ai\\.ollama|OLLAMA_BASE_URL|OLLAMA_CHAT_MODEL|OLLAMA_EMBED_MODEL|OLLAMA_KEEP_ALIVE"
                    + "|OLLAMA_NUM_CTX|org\\.springframework\\.ai\\.ollama|ollama:\\d+|ollama\\.com");

    @Test
    void theConfigurationHasOnlyTheGatewayAddressAndTheVirtualKey() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Path.of("src/main/resources"))) {
            files = walk.filter(path -> path.getFileName().toString().startsWith("application")).toList();
        }
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            String text = Files.readString(file);
            assertThat(MODEL_SERVER.matcher(text).find()).as(file.toString()).isFalse();
        }
        assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
                .contains("openai:").contains("SPRING_AI_OPENAI_BASE_URL").contains("SPRING_AI_OPENAI_API_KEY");
    }

    @Test
    void theSourceCodeDoesNotNameAModelServerAddressOrClient() throws IOException {
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java"))) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                assertThat(MODEL_SERVER.matcher(text).find()).as(file.toString()).isFalse();
            }
        }
    }
}
