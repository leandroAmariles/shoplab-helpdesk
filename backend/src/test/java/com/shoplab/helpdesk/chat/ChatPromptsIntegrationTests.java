package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.LangfuseStubs;
import com.shoplab.helpdesk.chat.support.EmptyKnowledgeContextConfig;
import com.shoplab.helpdesk.support.PostgresTest;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The prompts of the chat come from Langfuse and an evaluation can fix versions (LAB-85), end to end over HTTP: the
 * backend loads the prompts from a simulated Langfuse at start-up and asks a simulated model. What the model receives
 * is what proves which version of each prompt a request used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "shoplab.chat.rag.no-context-mode=model",
    "shoplab.chat.prompts.enabled=true",
    "shoplab.chat.prompts.public-key=pk-test",
    "shoplab.chat.prompts.secret-key=sk-test",
    "shoplab.chat.prompts.refresh-interval=1h",
    "shoplab.chat.prompts.override-token=token-de-evaluacion"})
@PostgresTest
@Import(EmptyKnowledgeContextConfig.class)
class ChatPromptsIntegrationTests {

    private static final String TOKEN = "token-de-evaluacion";

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    /** Its stubs are defined once, before the application starts, so they must survive between tests. */
    @RegisterExtension
    static WireMockExtension langfuse = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).resetOnEachTest(false).build();

    @DynamicPropertySource
    static void servers(DynamicPropertyRegistry registry) {
        // The application loads the prompts while it starts, so Langfuse has to answer before.
        LangfuseStubs.allValid(langfuse, 1);
        LangfuseStubs.text(langfuse, PromptName.CHAT_SYSTEM, "version", "5", 5, "Sistema v5");
        LangfuseStubs.notFound(langfuse, PromptName.CHAT_SYSTEM, "version", "99");
        registry.add("spring.ai.openai.base-url", () -> ollama.baseUrl() + "/v1");
        registry.add("shoplab.chat.prompts.base-url", langfuse::baseUrl);
    }

    @LocalServerPort
    private int port;

    @BeforeEach
    void stubTheModel() {
        ollama.resetAll();
        GatewayStubs.stubAnyChat(ollama, GatewayStubs.streaming(GatewayStubs.body("ok")));
    }

    private WebTestClient.ResponseSpec post(String versions, String token) {
        WebTestClient.RequestBodySpec request = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build().post().uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM);
        if (versions != null) {
            request.header("X-Prompt-Versions", versions);
        }
        if (token != null) {
            request.header("X-Prompt-Token", token);
        }
        return request.bodyValue("{\"message\":\"¿Cuánto tarda un envío?\"}").exchange();
    }

    private void drain(WebTestClient.ResponseSpec response) {
        response.returnResult(String.class).getResponseBody().collectList().block(Duration.ofSeconds(30));
    }

    private List<String> systemPromptsSent() {
        return ollama.findAll(postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH))).stream()
                .map(LoggedRequest::getBodyAsString)
                .map(body -> (String) JsonPath.read(body, "$.messages[0].content")).toList();
    }

    @Test
    void aRequestWithoutHeadersUsesTheVersionLabelledForProductionThatTheBackendLoadedAtStartUp() {
        WebTestClient.ResponseSpec response = post(null, null).expectStatus().isOk();
        drain(response);

        assertThat(systemPromptsSent()).containsExactly("Sistema v1");
    }

    @Test
    void anEvaluationRequestUsesTheVersionItFixesAndTheNextCustomerKeepsProduction() {
        drain(post("chat-system=5", TOKEN).expectStatus().isOk());
        drain(post(null, null).expectStatus().isOk());

        assertThat(systemPromptsSent()).containsExactly("Sistema v5", "Sistema v1");
    }

    @Test
    void aRequestThatFixesVersionsWithoutTheRightTokenIsForbiddenAndTheModelIsNeverCalled() {
        post("chat-system=5", null).expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00008");
        post("chat-system=5", "otro-token").expectStatus().isForbidden();

        assertThat(systemPromptsSent()).isEmpty();
    }

    @Test
    void aMalformedHeaderIsABadRequestBeforeAnythingIsAsked() {
        post("chat-system=abc", TOKEN).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00009");

        assertThat(systemPromptsSent()).isEmpty();
    }

    @Test
    void aVersionThatDoesNotExistFailsTheRequestAndNeverFallsBackToProduction() {
        post("chat-system=99", TOKEN).expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00010");

        assertThat(systemPromptsSent()).isEmpty();
    }
}
