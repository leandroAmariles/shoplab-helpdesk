package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Test doubles of the model gateway (LAB-84): an OpenAI-compatible server. Chat is {@code POST /v1/chat/completions}
 * answered as server-sent events (one {@code data:} line per chunk, an optional usage chunk and {@code [DONE]});
 * embeddings are {@code POST /v1/embeddings}. The bodies follow the OpenAI wire format that LiteLLM serves; the error
 * bodies are the ones LiteLLM uses for an exhausted budget, a bad key and an unknown model.
 */
public final class GatewayStubs {

    public static final String CHAT_PATH = "/v1/chat/completions";
    public static final String EMBEDDINGS_PATH = "/v1/embeddings";
    /** What the gateway reports as the model of a chunk answered by the primary model: its model group. */
    public static final String PRIMARY_MODEL = "chat";
    /** The model group of the fallback model, as the gateway reports it. */
    public static final String FALLBACK_MODEL = "chat-fallback";

    public static final String FIXTURE_TEXT = "Hola señor 😀, ¿en qué puedo ayudarle?";
    public static final int FIXTURE_FRAGMENTS = 6;
    private static final String[] FIXTURE = {"Hola", " se", "ñor", " 😀", ", ¿en", " qué puedo ayudarle?"};

    private static final String SSE = "text/event-stream";
    private static final String JSON = "application/json";

    private GatewayStubs() {
    }

    /** The streaming response of the reference answer {@link #FIXTURE_TEXT}, in {@link #FIXTURE_FRAGMENTS} chunks. */
    public static byte[] fixtureBody() {
        return body(FIXTURE);
    }

    /** A streaming body with the given fragments, answered by the primary model, without token counts. */
    public static byte[] body(String... fragments) {
        return stream(PRIMARY_MODEL, null, null, fragments);
    }

    /** Like {@link #body(String...)} but the last chunk carries the usage the way the gateway reports it. */
    public static byte[] bodyWithUsage(int promptTokens, int outputTokens, String... fragments) {
        return stream(PRIMARY_MODEL, promptTokens, outputTokens, fragments);
    }

    /** A body answered by another model, as when the gateway fell back to the secondary one. */
    public static byte[] bodyServedBy(String model, String... fragments) {
        return stream(model, null, null, fragments);
    }

    /**
     * The model asks to run one tool: the OpenAI streaming shape, a single chunk with the whole call, a
     * {@code tool_calls} finish reason and the usage of the round.
     */
    public static byte[] toolCallBody(String toolName, String argumentsJson, int promptTokens, int outputTokens) {
        String call = "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                + "\"type\":\"function\",\"function\":{\"name\":" + quote(toolName) + ",\"arguments\":"
                + quote(argumentsJson) + "}}]}";
        StringBuilder out = new StringBuilder();
        out.append(line(chunkJson(PRIMARY_MODEL, call, null)));
        out.append(line(chunkJson(PRIMARY_MODEL, "{}", "tool_calls")));
        out.append(line("{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":"
                + quote(PRIMARY_MODEL) + ",\"choices\":[],\"usage\":{\"prompt_tokens\":" + promptTokens
                + ",\"completion_tokens\":" + outputTokens + ",\"total_tokens\":" + (promptTokens + outputTokens)
                + "}}"));
        out.append("data: [DONE]\n\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** One chunk of a streaming answer as JSON, for servers that write the lines themselves. */
    public static String chunk(String content) {
        return chunkJson(PRIMARY_MODEL, "{\"content\":" + quote(content) + "}", null);
    }

    public static ResponseDefinitionBuilder streaming(byte[] body) {
        return aResponse().withStatus(200).withHeader("Content-Type", SSE).withBody(body);
    }

    /**
     * Matches the chat call whose question is exactly {@code userMessage}. The user message is no longer the bare
     * question (LAB-22): it comes from the context or empty-context template, and since LAB-114 the question sits
     * between the {@code <mensaje_del_cliente>} delimiters.
     */
    public static MappingBuilder chatFor(String userMessage) {
        return post(urlEqualTo(CHAT_PATH)).withRequestBody(matchingJsonPath("$.messages[1].content",
                matching("(?s).*Pregunta del cliente: <mensaje_del_cliente>\\n" + Pattern.quote(userMessage)
                        + "\\n</mensaje_del_cliente>\\s.*")));
    }

    public static void stubAnyChat(WireMockExtension gateway, ResponseDefinitionBuilder response) {
        gateway.stubFor(post(urlEqualTo(CHAT_PATH)).willReturn(response));
    }

    /** Embeddings of {@code count} texts, every vector with {@code dimensions} values. */
    public static ResponseDefinitionBuilder embeddings(int count, int dimensions) {
        String values = IntStream.range(0, dimensions).mapToObj(i -> "0.01").collect(Collectors.joining(","));
        String data = IntStream.range(0, count)
                .mapToObj(i -> "{\"object\":\"embedding\",\"index\":" + i + ",\"embedding\":[" + values + "]}")
                .collect(Collectors.joining(","));
        return aResponse().withStatus(200).withHeader("Content-Type", JSON).withBody("{\"object\":\"list\",\"data\":["
                + data + "],\"model\":\"bge-m3\",\"usage\":{\"prompt_tokens\":3,\"total_tokens\":3}}");
    }

    public static void stubAnyEmbeddings(WireMockExtension gateway, ResponseDefinitionBuilder response) {
        gateway.stubFor(post(urlEqualTo(EMBEDDINGS_PATH)).willReturn(response));
    }

    /** The gateway refuses the call because the virtual key spent its budget (HTTP 422, type budget_exceeded). */
    public static ResponseDefinitionBuilder budgetExceeded() {
        return error(422, "budget_exceeded",
                "Budget has been exceeded! Current cost: 0.0042, Max budget: 0.0001 token-secreto-123");
    }

    /** The gateway does not know the key (HTTP 401). */
    public static ResponseDefinitionBuilder unauthorized() {
        return error(401, "token_not_found_in_db", "Authentication Error, invalid key token-secreto-123");
    }

    /** The key cannot call the requested model, or there is none with that name (HTTP 403, as LiteLLM answers). */
    public static ResponseDefinitionBuilder unknownModel() {
        return error(403, "key_model_access_denied", "The requested model is not available token-secreto-123");
    }

    /** The error envelope of the OpenAI API: the message is free text that may even quote the prompt. */
    public static ResponseDefinitionBuilder error(int status, String type, String message) {
        return aResponse().withStatus(status).withHeader("Content-Type", JSON).withBody("{\"error\":{\"message\":"
                + quote(message) + ",\"type\":" + quote(type) + ",\"param\":null,\"code\":\"" + status + "\"}}");
    }

    private static byte[] stream(String model, Integer promptTokens, Integer completionTokens, String... fragments) {
        StringBuilder out = new StringBuilder();
        out.append(line(chunkJson(model, "{\"role\":\"assistant\",\"content\":\"\"}", null)));
        for (String fragment : fragments) {
            out.append(line(chunkJson(model, "{\"content\":" + quote(fragment) + "}", null)));
        }
        out.append(line(chunkJson(model, "{}", "stop")));
        if (promptTokens != null && completionTokens != null) {
            out.append(line("{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":"
                    + quote(model) + ",\"choices\":[],\"usage\":{\"prompt_tokens\":" + promptTokens
                    + ",\"completion_tokens\":" + completionTokens + ",\"total_tokens\":"
                    + (promptTokens + completionTokens) + "}}"));
        }
        out.append("data: [DONE]\n\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String chunkJson(String model, String delta, String finishReason) {
        return "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":" + quote(model)
                + ",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
                + (finishReason == null ? "null" : quote(finishReason)) + "}]}";
    }

    private static String line(String json) {
        return "data: " + json + "\n\n";
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** Fragments as a list, for tests that build them dynamically. */
    public static byte[] body(List<String> fragments) {
        return body(fragments.toArray(String[]::new));
    }
}
