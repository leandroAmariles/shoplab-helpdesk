package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.shoplab.helpdesk.chat.application.port.out.ConversationMemoryPort;
import com.shoplab.helpdesk.chat.domain.model.ConversationId;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.infrastructure.out.persistence.JdbcConversationMemoryAdapter;
import com.shoplab.helpdesk.retrieval.application.port.in.IndexDocumentsServicePort;
import com.shoplab.helpdesk.retrieval.application.port.in.IngestDocumentServicePort;
import com.shoplab.helpdesk.retrieval.domain.model.IngestDocumentCommand;
import com.shoplab.helpdesk.retrieval.support.DeterministicEmbeddingPort;
import com.shoplab.helpdesk.support.PostgresTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The memory of the conversation through the whole chat: a real PostgreSQL stores the turns, the question finds its
 * document (the embedding double only matches identical text, so a hit proves what was searched with), and the
 * simulated Ollama shows what the model received (specs/LAB-24).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "shoplab.chat.rag.no-context-mode=model", "shoplab.chat.memory.max-turns=3"})
@PostgresTest
@Import(ChatMemoryIntegrationTests.EmbeddingDouble.class)
class ChatMemoryIntegrationTests {

    private static final String SOURCE = "memory-test-shipping.md";
    private static final String MODEL = "memory-model";
    private static final String QUESTION = "it-cuanto tarda el envio estandar?";
    private static final String FOLLOW_UP = "it-y si es internacional?";
    private static final String PARSED = """
            {"document":{"name":"memory-test-shipping.md","title":"Politica de envios","format":"markdown"},
             "chunks":[
              {"text":"it-cuanto tarda el envio estandar?\\nit-y si es internacional?",
               "metadata":{"document":"memory-test-shipping.md","section":"Envios > Plazos","position":0}}]}
            """;

    @TestConfiguration
    static class EmbeddingDouble {

        @Bean
        @Primary
        DeterministicEmbeddingPort memoryEmbeddingPort() {
            return new DeterministicEmbeddingPort(MODEL);
        }
    }

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @RegisterExtension
    static WireMockExtension ingest = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void servers(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", ollama::baseUrl);
        registry.add("shoplab.retrieval.ingest.base-url", ingest::baseUrl);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private IngestDocumentServicePort ingestion;

    @Autowired
    private IndexDocumentsServicePort indexing;

    @Autowired
    private DeterministicEmbeddingPort embedding;

    @Autowired
    private JdbcTemplate jdbc;

    private String conversation;

    @BeforeEach
    void prepare() {
        ollama.resetAll();
        ingest.resetAll();
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(PARSED)));
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("Tarda tres dias ", "[1].")));
        embedding.recover();
        embedding.useModel(MODEL);
        embedding.resetCounters();
        conversation = UUID.randomUUID().toString();
        ingestion.ingest(new IngestDocumentCommand(SOURCE, "# Envios\n\nTexto.".getBytes(StandardCharsets.UTF_8)))
                .block();
        indexing.indexPending(false).block();
    }

    @AfterEach
    void removeTestData() {
        jdbc.update("DELETE FROM chat.conversation_turn WHERE question LIKE 'it-%'");
        jdbc.update("DELETE FROM retrieval.document WHERE source LIKE 'memory-test-%'");
    }

    private ChatTestClient.Result ask(String message, String conversationId) {
        String id = conversationId == null ? "" : ",\"conversationId\":\"" + conversationId + "\"";
        return new ChatTestClient(port).post("{\"message\":\"" + message + "\"" + id + "}");
    }

    private int storedTurns(String conversationId) {
        return jdbc.queryForObject("SELECT count(*) FROM chat.conversation_turn WHERE conversation_id = ?::uuid",
                Integer.class, conversationId);
    }

    private List<LoggedRequest> modelRequests() {
        return ollama.findAll(postRequestedFor(urlEqualTo("/api/chat")));
    }

    private LoggedRequest lastModelRequest() {
        List<LoggedRequest> requests = modelRequests();
        return requests.get(requests.size() - 1);
    }

    private String prometheus() {
        try {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // --- US1: a follow-up is understood ---

    @Test
    void aFollowUpIsSearchedWithThePreviousQuestionAndTheModelGetsTheEarlierExchange() {
        ChatTestClient.Result first = ask(QUESTION, conversation);
        assertThat(first.events()).extracting(ChatTestClient.Event::name).doesNotContain("sources");
        assertThat(storedTurns(conversation)).isEqualTo(1);

        ChatTestClient.Result followUp = ask(FOLLOW_UP, conversation);

        assertThat(followUp.status()).isEqualTo(200);
        assertThat(followUp.events()).extracting(ChatTestClient.Event::name).startsWith("sources");
        assertThat(followUp.events().get(0).data()).contains("\"title\":\"Politica de envios\"")
                .contains("\"number\":1");
        String body = lastModelRequest().getBodyAsString();
        assertThat(body).contains("\"role\":\"user\",\"content\":\"" + QUESTION + "\"")
                .contains("\"role\":\"assistant\",\"content\":\"Tarda tres dias.\"")
                .doesNotContain("Tarda tres dias [1]");
        assertThat(storedTurns(conversation)).isEqualTo(2);
    }

    @Test
    void theSameFollowUpWithoutTheEarlierQuestionFindsNothing() {
        ChatTestClient.Result alone = ask(FOLLOW_UP, UUID.randomUUID().toString());

        assertThat(alone.events()).extracting(ChatTestClient.Event::name).doesNotContain("sources");
        assertThat(lastModelRequest().getBodyAsString()).doesNotContain(QUESTION);
    }

    @Test
    void theTurnIsAlreadyStoredWhenTheStreamEndsAndTheIdentifierIsCaseInsensitive() {
        ask(QUESTION, conversation.toUpperCase());

        assertThat(storedTurns(conversation)).isEqualTo(1);
        assertThat(ask(FOLLOW_UP, conversation).events().get(0).name()).isEqualTo("sources");
    }

    // --- US2: isolation, persistence, errors ---

    @Test
    void twoConversationsNeverSeeEachOther() {
        String other = UUID.randomUUID().toString();
        ask(QUESTION, conversation);
        ollama.resetRequests();

        ask(FOLLOW_UP, other);

        assertThat(modelRequests()).hasSize(1);
        assertThat(lastModelRequest().getBodyAsString()).doesNotContain(QUESTION).doesNotContain("Tarda tres dias");
        assertThat(storedTurns(other)).isEqualTo(1);
        assertThat(storedTurns(conversation)).isEqualTo(1);
    }

    @Test
    void theTurnsSurviveANewMemoryInstanceOverTheSameDatabase() {
        ask(QUESTION, conversation);

        ConversationMemoryPort restarted = new JdbcConversationMemoryAdapter(jdbc);

        assertThat(restarted.recent(new ConversationId(conversation), 5).block())
                .containsExactly(new ConversationTurn(QUESTION, "Tarda tres dias [1]."));
    }

    @Test
    void aMessageWithoutConversationIdOrWithANullOneStoresNothing() {
        int before = jdbc.queryForObject("SELECT count(*) FROM chat.conversation_turn", Integer.class);

        assertThat(ask(QUESTION, null).status()).isEqualTo(200);
        assertThat(new ChatTestClient(port).post("{\"message\":\"" + QUESTION + "\",\"conversationId\":null}")
                .status()).isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat.conversation_turn", Integer.class))
                .isEqualTo(before);
    }

    @Test
    void anInvalidConversationIdIsABadRequestAndTheModelIsNotCalled() {
        ChatTestClient.Result result = ask(QUESTION, "no-es-un-uuid");

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.body()).contains("SLHD_CHAT_00005").doesNotContain("no-es-un-uuid");
        assertThat(modelRequests()).isEmpty();
    }

    @Test
    void anAnswerThatFailsBeforeItStartsStoresNothing() {
        ollama.stubFor(post(urlEqualTo("/api/chat")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        ChatTestClient.Result result = ask(QUESTION, conversation);

        assertThat(result.status()).isEqualTo(503);
        assertThat(storedTurns(conversation)).isZero();
    }

    @Test
    void twoRequestsOfTheSameConversationAtOnceLeaveTwoWholeTurns() {
        CompletableFuture<ChatTestClient.Result> one = CompletableFuture.supplyAsync(() -> ask("it-uno", conversation));
        CompletableFuture<ChatTestClient.Result> two = CompletableFuture.supplyAsync(() -> ask("it-dos", conversation));
        CompletableFuture.allOf(one, two).join();

        List<String> turns = jdbc.queryForList("""
                SELECT question || '=>' || answer FROM chat.conversation_turn WHERE conversation_id = ?::uuid
                """, String.class, conversation);
        assertThat(turns).containsExactlyInAnyOrder("it-uno=>Tarda tres dias [1].", "it-dos=>Tarda tres dias [1].");
        assertThat(ask("it-tres", conversation).status()).isEqualTo(200);
        assertThat(lastModelRequest().getBodyAsString()).contains("it-uno").contains("it-dos");
    }

    // --- US3: the history is bounded ---

    @Test
    void onlyTheLastTurnsWithinTheLimitReachTheModelButEverythingStaysStored() {
        for (String question : List.of("it-uno", "it-dos", "it-tres", "it-cuatro", "it-cinco")) {
            assertThat(ask(question, conversation).status()).isEqualTo(200);
        }

        ask("it-seis", conversation);

        String body = lastModelRequest().getBodyAsString();
        assertThat(body).contains("it-tres").contains("it-cuatro").contains("it-cinco").contains("it-seis")
                .doesNotContain("it-uno").doesNotContain("it-dos");
        assertThat(storedTurns(conversation)).isEqualTo(6);
    }

    // --- US5: nothing about the conversation in the metrics ---

    @Test
    void theMetricsCountTheHistoryWithoutTheConversationIdNorAnyText() {
        ask(QUESTION, conversation);
        ask(FOLLOW_UP, conversation);

        String metrics = prometheus();

        assertThat(metrics).contains("shoplab_chat_history_messages_count")
                .doesNotContain(conversation).doesNotContain("it-cuanto").doesNotContain("Tarda tres");
    }

    @Test
    void theModelIsCalledOncePerRequest() {
        ask(QUESTION, conversation);

        ollama.verify(exactly(1), postRequestedFor(urlEqualTo("/api/chat")));
    }
}
