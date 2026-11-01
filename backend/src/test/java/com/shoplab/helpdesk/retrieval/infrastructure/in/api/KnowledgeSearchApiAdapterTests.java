package com.shoplab.helpdesk.retrieval.infrastructure.in.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.retrieval.api.KnowledgeChunk;
import com.shoplab.helpdesk.retrieval.application.port.in.SearchChunksServicePort;
import com.shoplab.helpdesk.retrieval.domain.model.SearchResult;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class KnowledgeSearchApiAdapterTests {

    private final SearchChunksServicePort search = mock(SearchChunksServicePort.class);
    private final KnowledgeSearchApiAdapter adapter = new KnowledgeSearchApiAdapter(search,
            new KnowledgeChunkMapper());

    @Test
    void mapsEachResultToAPublicChunkWithoutInternalIdentifiers() {
        SearchResult result = new SearchResult(UUID.randomUUID(), UUID.randomUUID(), "returns.md",
                "Returns > Deadlines", 3, "Seven days.", 0.82);
        when(search.search("returns", 4)).thenReturn(Mono.just(List.of(result)));

        StepVerifier.create(adapter.search("returns", 4))
                .assertNext(chunks -> assertThat(chunks).containsExactly(
                        new KnowledgeChunk("returns.md", "Returns > Deadlines", 3, "Seven days.", 0.82)))
                .verifyComplete();
        verify(search).search("returns", 4);
    }

    @Test
    void anEmptySearchIsAnEmptyList() {
        when(search.search("nothing", 2)).thenReturn(Mono.just(List.of()));

        StepVerifier.create(adapter.search("nothing", 2))
                .expectNext(List.of())
                .verifyComplete();
    }

    @Test
    void theErrorOfTheSearchIsPropagatedUntouched() {
        RuntimeException failure = ExceptionUtil.technical(ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE, null);
        when(search.search("any", 1)).thenReturn(Mono.error(failure));

        StepVerifier.create(adapter.search("any", 1))
                .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                .verify();
    }
}
