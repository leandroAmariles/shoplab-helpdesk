package com.shoplab.helpdesk.retrieval.infrastructure.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.retrieval.application.port.in.SearchChunksServicePort;
import com.shoplab.helpdesk.retrieval.domain.model.SearchResult;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.handler.GlobalExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(KnowledgeSearchController.class)
@Import({RequestIdFilter.class, GlobalExceptionHandler.class})
class KnowledgeSearchControllerTests {

    private static final String MODEL = "bge-m3";

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private SearchChunksServicePort search;

    private static SearchResult result(String source, int position, double score) {
        return new SearchResult(UUID.randomUUID(), UUID.randomUUID(), source, "Titulo de " + source,
                "Seccion > Subseccion", position, "Texto de " + source, score);
    }

    @Test
    void returnsTheChunksInTheOrderOfTheSearchWithTheModel() {
        when(search.embeddingModel()).thenReturn(MODEL);
        when(search.search(eq("plazo de devolucion"), eq(10))).thenReturn(Mono.just(List.of(
                result("returns-01.md", 2, 0.91), result("payments-03.md", 0, 0.74),
                result("returns-01.md", 5, 0.5))));

        webTestClient.get().uri(uri -> uri.path("/api/knowledge/search")
                        .queryParam("query", "plazo de devolucion").queryParam("limit", 10).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.model").isEqualTo(MODEL)
                .jsonPath("$.results.length()").isEqualTo(3)
                .jsonPath("$.results[0].source").isEqualTo("returns-01.md")
                .jsonPath("$.results[0].title").isEqualTo("Titulo de returns-01.md")
                .jsonPath("$.results[0].section").isEqualTo("Seccion > Subseccion")
                .jsonPath("$.results[0].position").isEqualTo(2)
                .jsonPath("$.results[0].score").isEqualTo(0.91)
                .jsonPath("$.results[0].text").isEqualTo("Texto de returns-01.md")
                .jsonPath("$.results[1].source").isEqualTo("payments-03.md")
                .jsonPath("$.results[2].source").isEqualTo("returns-01.md")
                .jsonPath("$.results[2].position").isEqualTo(5);
    }

    @Test
    void doesNotExposeTheInternalIdentifiers() {
        when(search.embeddingModel()).thenReturn(MODEL);
        when(search.search(any(), any())).thenReturn(Mono.just(List.of(result("returns-01.md", 0, 0.9))));

        webTestClient.get().uri("/api/knowledge/search?query=devolucion")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.results[0].chunkId").doesNotExist()
                .jsonPath("$.results[0].documentId").doesNotExist();
    }

    @Test
    void passesTheLimitAsIsAndNullWhenItIsMissing() {
        when(search.embeddingModel()).thenReturn(MODEL);
        when(search.search(any(), any())).thenReturn(Mono.just(List.of()));

        webTestClient.get().uri("/api/knowledge/search?query=a&limit=50").exchange().expectStatus().isOk();
        webTestClient.get().uri("/api/knowledge/search?query=a").exchange().expectStatus().isOk();

        verify(search).search("a", 50);
        verify(search).search("a", null);
    }

    @Test
    void anEmptyListIsAnOkResponseWithNoResults() {
        when(search.embeddingModel()).thenReturn(MODEL);
        when(search.search(any(), any())).thenReturn(Mono.just(List.of()));

        webTestClient.get().uri("/api/knowledge/search?query=nada").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.model").isEqualTo(MODEL)
                .jsonPath("$.results.length()").isEqualTo(0);
    }

    @Test
    void aBlankQueryIsAnUnprocessableRequest() {
        when(search.search(any(), any())).thenReturn(
                Mono.error(ExceptionUtil.business(ResponseCodeEnum.SEARCH_QUERY_INVALID)));

        webTestClient.get().uri("/api/knowledge/search?query=%20%20").exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_RETR_00009");
    }

    @Test
    void aMissingQueryIsTheSameUnprocessableRequest() {
        when(search.search(eq(null), any())).thenReturn(
                Mono.error(ExceptionUtil.business(ResponseCodeEnum.SEARCH_QUERY_INVALID)));

        webTestClient.get().uri("/api/knowledge/search").exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_RETR_00009");
    }

    @Test
    void anUnavailableEmbeddingModelIsAServiceUnavailableResponseWithoutInternalDetail() {
        when(search.search(any(), any())).thenReturn(Mono.error(ExceptionUtil.technical(
                ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE, new IllegalStateException("secret internal detail"))));

        webTestClient.get().uri("/api/knowledge/search?query=a").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SLHD_RETR_00006")
                .consumeWith(body -> assertThat(new String(body.getResponseBody()))
                        .doesNotContain("secret internal detail"));
    }

    @Test
    void accentsAndQuestionMarksReachTheSearchIntact() {
        String question = "¿Cuántos días tengo para devolver un envío?";
        when(search.embeddingModel()).thenReturn(MODEL);
        when(search.search(eq(question), any())).thenReturn(Mono.just(List.of()));

        webTestClient.get().uri(uri -> uri.path("/api/knowledge/search").queryParam("query", question).build())
                .exchange()
                .expectStatus().isOk();

        verify(search).search(question, null);
    }
}
