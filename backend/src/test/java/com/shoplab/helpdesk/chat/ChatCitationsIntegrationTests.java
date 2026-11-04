package com.shoplab.helpdesk.chat;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * The sources of an answer go through the whole chat: the document is indexed in a real PostgreSQL, the question finds
 * it, the request to the (simulated) Ollama numbers the fragment and the event stream carries a {@code sources} event
 * with the readable title before the first token (specs/LAB-23, US1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PostgresTest
@Import(ChatCitationsIntegrationTests.EmbeddingDouble.class)
class ChatCitationsIntegrationTests {

    private static final String SOURCE = "cite-test-policy.md";
    private static final String MODEL = "cite-model";
    private static final String QUESTION_JSON = "{\"message\":\"Plazos\\n\\nTreinta dias.\"}";
    private static final String PARSED = """
            {"document":{"name":"cite-test-policy.md","title":"Politica de devoluciones","format":"markdown"},
             "chunks":[
              {"text":"Plazos\\n\\nTreinta dias.","metadata":{"document":"cite-test-policy.md",
               "section":"Politica > Plazos","position":0}}]}
            """;

    @TestConfiguration
    static class EmbeddingDouble {

        @Bean
        @Primary
        DeterministicEmbeddingPort citeEmbeddingPort() {
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

    @BeforeEach
    void prepare() {
        ollama.resetAll();
        ingest.resetAll();
        ingest.stubFor(post(urlEqualTo("/ingest")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(PARSED)));
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("Treinta dias ", "[1].")));
        embedding.recover();
        embedding.useModel(MODEL);
        embedding.resetCounters();
    }

    @AfterEach
    void removeTestDocuments() {
        jdbc.update("DELETE FROM retrieval.document WHERE source LIKE 'cite-test-%'");
    }

    private void indexTheDocument() {
        ingestion.ingest(new IngestDocumentCommand(SOURCE, "# Politica\n\n## Plazos\n\nTreinta dias."
                .getBytes(StandardCharsets.UTF_8))).block();
        indexing.indexPending(false).block();
    }

    private ChatTestClient.Result ask(String json) {
        return new ChatTestClient(port).post(json);
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

    /** Value of {@code shoplab_chat_citations_total} for one outcome; 0 while it has never been counted. */
    private double counter(String outcome) {
        Matcher line = Pattern.compile("shoplab_chat_citations_total\\{[^}]*outcome=\"" + outcome + "\"[^}]*} (\\S+)")
                .matcher(prometheus());
        return line.find() ? Double.parseDouble(line.group(1)) : 0.0;
    }

    @Test
    void theSourcesComeFirstWithTheReadableTitleThenTheTokensAndDone() {
        indexTheDocument();

        ChatTestClient.Result result = ask(QUESTION_JSON);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.events()).extracting(ChatTestClient.Event::name)
                .containsExactly("sources", "token", "token", "done");
        String sources = result.events().get(0).data();
        assertThat(sources).contains("\"number\":1").contains("\"title\":\"Politica de devoluciones\"")
                .contains("\"section\":\"Politica > Plazos\"").contains("Treinta dias.")
                .doesNotContain(SOURCE);
        assertThat(result.text()).isEqualTo("Treinta dias [1].");
        assertThat(result.last().data()).isEqualTo("{\"fragments\":2}");
    }

    @Test
    void theRequestToTheModelNumbersTheFragmentAndAsksToCiteIt() {
        indexTheDocument();

        ask(QUESTION_JSON);

        ollama.verify(exactly(1), postRequestedFor(urlEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.messages[0].content",
                        containing("termina con el número de su fragmento entre corchetes")))
                .withRequestBody(matchingJsonPath("$.messages[1].content",
                        matching("(?s).*\\[1] Politica de devoluciones › Politica > Plazos\\s+Plazos.*")))
                .withRequestBody(matchingJsonPath("$.messages[1].content", containing("Cita los fragmentos"))));
    }

    @Test
    void theQualityOfTheCitationsIsCountedWithoutAnyText() {
        indexTheDocument();
        String before = counter("valid") + "/" + counter("invalid") + "/" + counter("none");

        ask(QUESTION_JSON);
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("Treinta dias ", "[7].")));
        ask(QUESTION_JSON);
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("Treinta dias.")));
        ask(QUESTION_JSON);

        assertThat(counter("valid") + "/" + counter("invalid") + "/" + counter("none")).isNotEqualTo(before);
        assertThat(counter("valid")).isGreaterThanOrEqualTo(1.0);
        assertThat(counter("invalid")).isGreaterThanOrEqualTo(1.0);
        assertThat(counter("none")).isGreaterThanOrEqualTo(1.0);
        assertThat(prometheus()).doesNotContain("Treinta");
    }

    @Test
    void withoutContextThereIsNoSourcesEvent() {
        ChatTestClient.Result result = ask("{\"message\":\"¿Venden el modelo X999?\"}");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.events()).extracting(ChatTestClient.Event::name).isEqualTo(List.of("token", "done"));
    }
}
