package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.config.PromptProperties;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** What Langfuse answers (as verified against the real image), for the tests of the prompt client and catalog. */
public final class LangfuseStubs {

    public static final String PUBLIC_KEY = "pk-test";
    public static final String SECRET_KEY = "sk-test";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LangfuseStubs() {
    }

    public static PromptProperties properties(WireMockExtension langfuse, Duration requestTimeout) {
        return new PromptProperties(true, langfuse.baseUrl(), PUBLIC_KEY, SECRET_KEY, "production",
                Duration.ofSeconds(60), requestTimeout, null, false, null);
    }

    /** A version of a prompt of type text, found by the label of the query. */
    public static void text(WireMockExtension langfuse, PromptName name, String queryKey, String queryValue, int version,
            String text) {
        found(langfuse, name, queryKey, queryValue, version, "text", text);
    }

    /** A version of a prompt of type chat: its {@code prompt} is a list of messages, not a text. */
    public static void chat(WireMockExtension langfuse, PromptName name, String queryKey, String queryValue,
            int version) {
        found(langfuse, name, queryKey, queryValue, version, "chat", java.util.List.of(
                Map.of("role", "system", "content", "x")));
    }

    public static void notFound(WireMockExtension langfuse, PromptName name, String queryKey, String queryValue) {
        langfuse.stubFor(get(urlPathEqualTo(path(name))).withQueryParam(queryKey, equalTo(queryValue))
                .willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Prompt not found: '" + name.langfuseName() + "' with " + queryKey
                                + " '" + queryValue + "'\",\"error\":\"LangfuseNotFoundError\"}")));
    }

    public static void status(WireMockExtension langfuse, PromptName name, String queryKey, String queryValue,
            int status) {
        langfuse.stubFor(get(urlPathEqualTo(path(name))).withQueryParam(queryKey, equalTo(queryValue))
                .willReturn(aResponse().withStatus(status)));
    }

    /** The four prompts of a label with valid texts that say their version. */
    public static void allValid(WireMockExtension langfuse, int version) {
        text(langfuse, PromptName.CHAT_SYSTEM, "label", "production", version, systemText(version));
        text(langfuse, PromptName.SMALL_TALK, "label", "production", version, smallTalkText(version));
        text(langfuse, PromptName.CONTEXT, "label", "production", version, contextText(version));
        text(langfuse, PromptName.EMPTY_CONTEXT, "label", "production", version, emptyContextText(version));
    }

    public static String systemText(int version) {
        return "Sistema v" + version;
    }

    public static String smallTalkText(int version) {
        return "Cortesía v" + version + " temas: {topics}";
    }

    public static String contextText(int version) {
        return "Contexto v" + version + " {context} || {query}";
    }

    public static String emptyContextText(int version) {
        return "Sin contexto v" + version + " {query}";
    }

    public static String path(PromptName name) {
        return "/api/public/v2/prompts/" + name.langfuseName();
    }

    private static void found(WireMockExtension langfuse, PromptName name, String queryKey, String queryValue,
            int version, String type, Object prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name.langfuseName());
        body.put("version", version);
        body.put("type", type);
        body.put("prompt", prompt);
        body.put("labels", java.util.List.of("production"));
        body.put("config", Map.of());
        body.put("isActive", true);
        langfuse.stubFor(get(urlPathEqualTo(path(name))).withQueryParam(queryKey, equalTo(queryValue))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(JSON.writeValueAsString(body))));
    }
}
