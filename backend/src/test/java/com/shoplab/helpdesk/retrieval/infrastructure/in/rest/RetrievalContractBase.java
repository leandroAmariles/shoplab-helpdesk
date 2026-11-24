package com.shoplab.helpdesk.retrieval.infrastructure.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.shoplab.helpdesk.retrieval.application.port.in.SearchChunksServicePort;
import com.shoplab.helpdesk.retrieval.domain.model.SearchResult;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.handler.GlobalExceptionHandler;
import io.restassured.module.webtestclient.RestAssuredWebTestClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/** Base class for Spring Cloud Contract verification of {@code retrieval} HTTP endpoints. */
public abstract class RetrievalContractBase {

    @BeforeEach
    void setup() {
        SearchChunksServicePort searchPort = mock(SearchChunksServicePort.class);
        lenient().when(searchPort.embeddingModel()).thenReturn("bge-m3");
        lenient().when(searchPort.search(eq("politicas de devolucion"), eq(5)))
                .thenReturn(Mono.just(List.of(new SearchResult(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "returns-policy.md",
                        "Política de devoluciones",
                        "Plazos",
                        0,
                        "El plazo estándar de devolución es de 30 días naturales desde la entrega.",
                        0.91))));
        lenient().when(searchPort.search(eq("consulta sin coincidencias"), eq(5)))
                .thenReturn(Mono.just(List.of()));
        lenient().when(searchPort.search(eq("   "), any()))
                .thenReturn(Mono.error(ExceptionUtil.business(ResponseCodeEnum.SEARCH_QUERY_INVALID)));

        KnowledgeSearchController controller = new KnowledgeSearchController(searchPort);
        WebTestClient client = WebTestClient.bindToController(controller)
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
        RestAssuredWebTestClient.webTestClient(client);
    }
}
