package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Test doubles of Ollama's streaming {@code POST /api/chat}: newline-delimited JSON, one line per fragment. */
public final class OllamaStubs {

    public static final String FIXTURE_TEXT = "Hola señor 😀, ¿en qué puedo ayudarle?";
    public static final int FIXTURE_FRAGMENTS = 6;

    private static final String NDJSON = "application/x-ndjson";

    private OllamaStubs() {
    }

    /** The recorded response in {@code ollama/chat-stream.ndjson}. */
    public static byte[] fixtureBody() {
        try (InputStream in = OllamaStubs.class.getResourceAsStream("/ollama/chat-stream.ndjson")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A streaming body with the given fragments plus the final {@code done} line. */
    public static byte[] body(String... fragments) {
        String lines = Arrays.stream(fragments)
                .map(f -> "{\"model\":\"llama3.2:3b\",\"message\":{\"role\":\"assistant\",\"content\":\""
                        + f.replace("\\", "\\\\").replace("\"", "\\\"") + "\"},\"done\":false}")
                .collect(Collectors.joining("\n"));
        String last = "{\"model\":\"llama3.2:3b\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
                + "\"done\":true,\"done_reason\":\"stop\"}";
        return ((lines.isEmpty() ? "" : lines + "\n") + last + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static ResponseDefinitionBuilder streaming(byte[] body) {
        return aResponse().withStatus(200).withHeader("Content-Type", NDJSON).withBody(body);
    }

    /** Matches the chat call whose user message is exactly {@code userMessage}. */
    public static MappingBuilder chatFor(String userMessage) {
        return post(urlEqualTo("/api/chat")).withRequestBody(
                matchingJsonPath("$.messages[?(@.role == 'user' && @.content == '" + userMessage + "')]"));
    }

    public static void stubAnyChat(WireMockExtension ollama, ResponseDefinitionBuilder response) {
        ollama.stubFor(post(urlEqualTo("/api/chat")).willReturn(response));
    }
}
