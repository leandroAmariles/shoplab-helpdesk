package com.shoplab.helpdesk.chat.infrastructure.out.knowledge;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.config.RetrievalClientConfig;
import com.shoplab.helpdesk.chat.config.RetrievalClientProperties;
import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** The behaviour of specs/LAB-129-retrieval-service-extraction/contracts/chat-client.md. */
class HttpKnowledgeSearchClientTests {

    private static final String PATH = "/api/knowledge/search";

    @RegisterExtension
    static WireMockExtension retrieval = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    private final HttpKnowledgeSearchClient client = clientWithTimeout(Duration.ofSeconds(5));

    private HttpKnowledgeSearchClient clientWithTimeout(Duration timeout) {
        var properties = new RetrievalClientProperties(retrieval.baseUrl(), timeout);
        var config = new RetrievalClientConfig();
        return new HttpKnowledgeSearchClient(
                config.retrievalSearchApi(WebClient.builder(), properties), properties.timeout());
    }

    private static void answer(ResponseDefinitionBuilder response) {
        retrieval.stubFor(get(urlPathEqualTo(PATH)).willReturn(response));
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String hit(String source, String title, int position, String text) {
        return """
                {"source":"%s","title":"%s","section":"Plazos","position":%d,"score":0.9,"text":"%s"}
                """.formatted(source, title, position, text);
    }

    private static void results(String... hits) {
        answer(json(200, "{\"model\":\"bge-m3\",\"results\":[" + String.join(",", hits) + "]}"));
    }

    @Test
    void keepsTheOrderOfTheResultsAndAsksWithTheQuestionAndTheLimit() {
        results(hit("b.md", "B", 1, "segundo"), hit("a.md", "A", 0, "primero"));

        List<ContextFragment> fragments = client.retrieve("devoluciones", 4).block();

        assertThat(fragments).extracting(ContextFragment::source).containsExactly("b.md", "a.md");
        retrieval.verify(getRequestedFor(urlPathEqualTo(PATH)).withQueryParam("query", equalTo("devoluciones"))
                .withQueryParam("limit", equalTo("4")));
    }

    @Test
    void dropsHitsWithoutTextAndDerivesAMissingTitleFromTheFileName() {
        results(hit("vacio.md", "Vacio", 0, "  "), hit("returns-01-policy.md", " ", 1, "texto"));

        List<ContextFragment> fragments = client.retrieve("x", 4).block();

        assertThat(fragments).singleElement()
                .extracting(ContextFragment::title).isEqualTo("Returns 01 policy");
    }

    @Test
    void noResultsIsAnEmptyContextNotAFailure() {
        results();

        StepVerifier.create(client.retrieve("x", 4)).expectNext(List.of()).verifyComplete();
    }

    static ResponseDefinitionBuilder[] failures() {
        return new ResponseDefinitionBuilder[] {
            json(422, "{\"code\":\"SLHD_RETR_00009\",\"message\":\"m\"}"),
            json(503, "{\"code\":\"SLHD_RETR_00006\",\"message\":\"m\"}"),
            json(200, "not json"),
            aResponse().withStatus(200),
            aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)
        };
    }

    @ParameterizedTest
    @MethodSource("failures")
    void anyFailureIsTheRetrievalErrorOfTheChatAndIsNotRetried(ResponseDefinitionBuilder response) {
        answer(response);

        expectContextUnavailable(client);

        assertThat(retrieval.getAllServeEvents()).hasSize(1);
    }

    @Test
    void doesNotWaitLongerThanItsTimeout() {
        answer(json(200, "{\"model\":\"bge-m3\",\"results\":[]}").withFixedDelay(2000));

        expectContextUnavailable(clientWithTimeout(Duration.ofMillis(200)));
    }

    private static void expectContextUnavailable(HttpKnowledgeSearchClient search) {
        StepVerifier.create(search.retrieve("x", 4))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(KnowledgeRetrievalException.class);
                    assertThat(((KnowledgeRetrievalException) error).getResponseCode())
                            .isEqualTo(ResponseCodeEnum.CHAT_CONTEXT_UNAVAILABLE);
                })
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void forwardsTheCorrelationIdAndTheTraceOfTheRequest() {
        results();
        Context request = Context.of(RequestIdFilter.MDC_KEY, "req-129", RequestIdFilter.TRACEPARENT_KEY,
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        client.retrieve("x", 4).contextWrite(request).block();

        retrieval.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withHeader("X-Request-Id", equalTo("req-129"))
                .withHeader("traceparent",
                        equalTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01")));
    }

    static String[] questions() {
        return new String[] {"€".repeat(4000), "¿{x} & a+b #1 100% {} ? devolución"};
    }

    /**
     * Against a server with the request line limit of the service (64 KB): the WireMock server refuses the longest
     * question whatever its header buffer is set to.
     */
    @ParameterizedTest
    @MethodSource("questions")
    void theQuestionArrivesAsItWasWritten(String question) {
        AtomicReference<String> asked = new AtomicReference<>();
        DisposableServer server = HttpServer.create().port(0)
                .httpRequestDecoder(decoder -> decoder.maxInitialLineLength(65_536))
                .handle((request, response) -> {
                    asked.set(request.uri());
                    return response.header("Content-Type", "application/json")
                            .sendString(Mono.just("{\"model\":\"bge-m3\",\"results\":[]}"));
                })
                .bindNow();
        try {
            var properties = new RetrievalClientProperties("http://localhost:" + server.port(), null);
            var search = new HttpKnowledgeSearchClient(
                    new RetrievalClientConfig().retrievalSearchApi(WebClient.builder(), properties),
                    properties.timeout());

            search.retrieve(question, 4).block();

            String query = asked.get().substring(asked.get().indexOf("query=") + 6, asked.get().indexOf("&limit="));
            // Decoded as a server does it: an unencoded plus would turn into a space.
            assertThat(URLDecoder.decode(query, StandardCharsets.UTF_8)).isEqualTo(question);
        } finally {
            server.disposeNow();
        }
    }
}
