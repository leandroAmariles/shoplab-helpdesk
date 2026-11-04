package com.shoplab.helpdesk.chat.infrastructure.out.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.retrieval.api.KnowledgeChunk;
import com.shoplab.helpdesk.retrieval.api.KnowledgeSearchApi;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(OutputCaptureExtension.class)
class KnowledgeSearchAdapterTests {

    private final KnowledgeSearchApi search = mock(KnowledgeSearchApi.class);
    private final KnowledgeSearchAdapter adapter = new KnowledgeSearchAdapter(search, new ContextFragmentMapper());

    @Test
    void mapsTheChunksToFragmentsKeepingTheOrder() {
        when(search.search("returns", 4)).thenReturn(Mono.just(List.of(
                new KnowledgeChunk("a.md", "Title of a.md", "Returns", 1, "Seven days.", 0.9),
                new KnowledgeChunk("b.md", "Title of b.md", "", 0, "Refund in ten days.", 0.7))));

        StepVerifier.create(adapter.retrieve("returns", 4))
                .expectNext(List.of(new ContextFragment("a.md", "Title of a.md", "Returns", 1, "Seven days.", 0.9),
                        new ContextFragment("b.md", "Title of b.md", "", 0, "Refund in ten days.", 0.7)))
                .verifyComplete();
        verify(search).search("returns", 4);
    }

    @Test
    void nothingFoundIsAnEmptyListNotAnError() {
        when(search.search("x", 4)).thenReturn(Mono.just(List.of()));

        StepVerifier.create(adapter.retrieve("x", 4)).expectNext(List.of()).verifyComplete();
    }

    @Test
    void theTitleOfTheDocumentTravelsWithTheFragment() {
        when(search.search("x", 1)).thenReturn(Mono.just(List.of(
                new KnowledgeChunk("returns-01-return-policy.md", "Return policy", "S", 0, "t", 0.5))));

        StepVerifier.create(adapter.retrieve("x", 1))
                .assertNext(fragments -> assertThat(fragments.get(0).title()).isEqualTo("Return policy"))
                .verifyComplete();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "returns-01-return-policy.md|Returns 01 return policy",
            "faq_payments.PDF|Faq payments",
            "notes|Notes"})
    void aMissingTitleIsDerivedFromTheFileName(String source, String expected) {
        when(search.search("x", 1)).thenReturn(Mono.just(List.of(
                new KnowledgeChunk(source, " ", "S", 0, "t", 0.5))));

        StepVerifier.create(adapter.retrieve("x", 1))
                .assertNext(fragments -> assertThat(fragments.get(0).title()).isEqualTo(expected))
                .verifyComplete();
    }

    @Test
    void aNullTitleIsDerivedToo() {
        when(search.search("x", 1)).thenReturn(Mono.just(List.of(
                new KnowledgeChunk("a.md", null, "S", 0, "t", 0.5))));

        StepVerifier.create(adapter.retrieve("x", 1))
                .assertNext(fragments -> assertThat(fragments.get(0).title()).isEqualTo("A"))
                .verifyComplete();
    }

    @Test
    void aNullSectionBecomesEmpty() {
        when(search.search("x", 1)).thenReturn(Mono.just(List.of(
                new KnowledgeChunk("a.md", "Title of a.md", null, 0, "t", 0.5))));

        StepVerifier.create(adapter.retrieve("x", 1))
                .assertNext(fragments -> assertThat(fragments.get(0).section()).isEmpty())
                .verifyComplete();
    }

    @Test
    void aChunkWithoutTextIsDroppedAndTheLogDoesNotSayWhatItHeld(CapturedOutput output) {
        when(search.search("x", 4)).thenReturn(Mono.just(List.of(
                new KnowledgeChunk("a.md", "Title of a.md", "S", 3, "   ", 0.9),
                new KnowledgeChunk("a.md", "Title of a.md", "S", 4, "Useful.", 0.8))));

        StepVerifier.create(adapter.retrieve("x", 4))
                .assertNext(fragments -> assertThat(fragments).extracting(ContextFragment::text)
                        .containsExactly("Useful."))
                .verifyComplete();
        assertThat(output.getOut()).contains("a.md").contains("position=3");
    }

    @Test
    void aTypedErrorOfTheSearchBecomesAKnowledgeRetrievalErrorKeepingTheCause() {
        RuntimeException cause = ExceptionUtil.technical(ResponseCodeEnum.EMBEDDING_SERVICE_UNAVAILABLE, null);
        when(search.search("x", 4)).thenReturn(Mono.error(cause));

        StepVerifier.create(adapter.retrieve("x", 4))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(KnowledgeRetrievalException.class)
                        .hasCause(cause)
                        .extracting(e -> ((KnowledgeRetrievalException) e).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.CHAT_CONTEXT_UNAVAILABLE))
                .verify();
    }

    @Test
    void anUnexpectedErrorOrAnExceptionWhenCallingTheSearchIsAlsoWrapped() {
        when(search.search("boom", 4)).thenThrow(new IllegalStateException("db"));
        when(search.search("fail", 4)).thenReturn(Mono.error(new IllegalStateException("db")));

        StepVerifier.create(adapter.retrieve("boom", 4)).expectError(KnowledgeRetrievalException.class).verify();
        StepVerifier.create(adapter.retrieve("fail", 4)).expectError(KnowledgeRetrievalException.class).verify();
    }
}
