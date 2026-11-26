package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.chat.support.StubKnowledgeContext;
import com.shoplab.helpdesk.chat.support.StubKnowledgeContextConfig;
import com.shoplab.helpdesk.support.PostgresTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A question goes through the whole chat: the knowledge base answers (a stub of retrieval-service), and
 * the request that reaches the (simulated) Ollama carries the right context, or says there is
 * none (specs/LAB-22, US1, US2 and US4). The contract of the event stream is the one of LAB-12.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PostgresTest
@Import(StubKnowledgeContextConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class RagAnsweringIntegrationTests {

    private static final String SOURCE = "rag-test-policy.md";
    private static final String QUESTION_JSON = "{\"message\":\"Plazos\\n\\nTreinta dias.\"}";

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void servers(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> ollama.baseUrl() + "/v1");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private StubKnowledgeContext knowledge;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        ollama.resetAll();
        GatewayStubs.stubAnyChat(ollama, GatewayStubs.streaming(GatewayStubs.body("Treinta ", "dias.")));
        knowledge.reset();
    }

    private void indexTheDocument() {
        knowledge.answers(new ContextFragment(SOURCE, "Politica", "Politica > Plazos", 0,
                "Plazos\n\nTreinta dias.", 1.0));
    }

    private ChatTestClient client() {
        return new ChatTestClient(port);
    }

    @Test
    void theRequestToTheModelCarriesTheChunkThatAnswersTheQuestionAndTheEventContractIsUnchanged() {
        indexTheDocument();

        ChatTestClient.Result result = client().post(QUESTION_JSON);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.header("Content-Type")).startsWith("text/event-stream");
        assertThat(result.text()).isEqualTo("Treinta dias.");
        assertThat(result.last().name()).isEqualTo("done");
        assertThat(result.last().data()).isEqualTo("{\"fragments\":2}");
        ollama.verify(exactly(1), postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH))
                .withRequestBody(matchingJsonPath("$.messages[0].role", containing("system")))
                .withRequestBody(matchingJsonPath("$.messages[0].content",
                        containing("solo con la información de la sección CONTEXTO")))
                .withRequestBody(matchingJsonPath("$.messages[1].content",
                        matching("(?s).*\\[1] Politica › Politica > Plazos\\s+Plazos\\s+Treinta dias\\..*")))
                .withRequestBody(matchingJsonPath("$.messages[1].content",
                        matching("(?s).*Pregunta del cliente: <mensaje_del_cliente>\\s+Plazos\\s+Treinta dias\\."
                                + "\\s+</mensaje_del_cliente>.*"))));
    }

    @Test
    void withNothingIndexedTheAnswerIsTheFixedMessageAndOllamaIsNotCalled() {
        ChatTestClient.Result result = client().post("{\"message\":\"¿Venden el modelo X999?\"}");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.text()).isEqualTo(ChatConstants.DEFAULT_NO_CONTEXT_MESSAGE);
        assertThat(result.last().name()).isEqualTo("done");
        assertThat(result.last().data()).isEqualTo("{\"fragments\":1}");
        ollama.verify(exactly(0), postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH)));
        assertThat(prometheus()).containsPattern(
                "shoplab_chat_context_retrieval_seconds_count\\{[^}]*outcome=\"no_context\"[^}]*} [1-9]");
    }

    @Test
    void whenTheSearchFailsTheAnswerIs503WithItsOwnCodeAndTheModelIsNeverCalled() {
        indexTheDocument();
        knowledge.fails();

        ChatTestClient.Result result = client().post(QUESTION_JSON);

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.body()).contains("SLHD_CHAT_00004").contains("The knowledge base is not available")
                .doesNotContain("SLHD_CHAT_00002");
        ollama.verify(exactly(0), postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH)));
        assertThat(prometheus()).containsPattern(
                "shoplab_chat_context_retrieval_seconds_count\\{[^}]*outcome=\"failed\"[^}]*} [1-9]");
    }

    @Test
    void aBlankMessageIsRejectedWithoutSearching() {
        ChatTestClient.Result result = client().post("{\"message\":\"   \"}");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body()).contains("SLHD_CHAT_00001");
        assertThat(knowledge.questions()).isEmpty();
        ollama.verify(exactly(0), postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH)));
    }

    @Test
    void metricsAndLogsDescribeTheContextWithoutTheQuestionOrTheChunkText(CapturedOutput output) {
        indexTheDocument();

        client().post(QUESTION_JSON.replace("Plazos", "SecretoDelCliente"));
        client().post(QUESTION_JSON);

        assertThat(prometheus()).contains("shoplab_chat_context_fragments_count")
                .containsPattern("shoplab_chat_context_retrieval_seconds_count\\{[^}]*outcome=\"with_context\"");
        assertThat(output.getOut()).contains("Context retrieved")
                .doesNotContain("SecretoDelCliente").doesNotContain("Treinta dias");
    }

    private String prometheus() {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
