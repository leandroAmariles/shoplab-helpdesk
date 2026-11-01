package com.shoplab.helpdesk.chat.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.application.port.out.ChatModelPort;
import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.config.ChatProperties;
import com.shoplab.helpdesk.chat.config.RagProperties;
import com.shoplab.helpdesk.chat.domain.exception.ChatModelException;
import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.enums.ContextOutcome;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.ErrorEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.enums.ChatOutcome;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

@ExtendWith(OutputCaptureExtension.class)
class StreamChatUseCaseTests {

    private static final int MAX_LENGTH = 4000;

    private final ChatMetricsPort metrics = mock(ChatMetricsPort.class);

    private final KnowledgeContextPort knowledge = mock(KnowledgeContextPort.class);

    {
        when(knowledge.retrieve(any(), anyInt())).thenReturn(Mono.just(List.of()));
    }

    private StreamChatUseCase newUseCase(ChatModelPort model) {
        return newUseCase(model, new RagProperties(null, null, null, null, null));
    }

    private StreamChatUseCase newUseCase(ChatModelPort model, RagProperties rag) {
        return new StreamChatUseCase(model, knowledge, new ContextSelector(), metrics,
                new ChatProperties(MAX_LENGTH, "system"), rag);
    }

    private StreamChatUseCase useCaseAnswering(Flux<String> answer) {
        ChatModelPort model = (message, context) -> answer;
        return newUseCase(model);
    }

    private static ContextFragment fragment(String text, double score) {
        return new ContextFragment("returns.md", "Returns", 0, text, score);
    }

    private StreamChatCommand command(String message) {
        return new StreamChatCommand(message, "req-1");
    }

    @Test
    void emitsOneTokenPerFragmentInOrderAndThenDoneWithTheCount() {
        StreamChatUseCase useCase = useCaseAnswering(Flux.just("Ho", "la", " ", "mundo"));

        StepVerifier.create(useCase.stream(command("Hola")))
                .expectNext(new TokenEvent("Ho"), new TokenEvent("la"), new TokenEvent(" "),
                        new TokenEvent("mundo"))
                .expectNext(new DoneEvent(4))
                .verifyComplete();
    }

    @Test
    void concatenatingTheTokensGivesTheWholeAnswer() {
        StreamChatUseCase useCase = useCaseAnswering(Flux.just("Hola", " se", "ñor", " 😀"));

        StepVerifier.create(useCase.stream(command("Hola")).ofType(TokenEvent.class).map(TokenEvent::content)
                        .collectList())
                .expectNextMatches(parts -> String.join("", parts).equals("Hola señor 😀"))
                .verifyComplete();
    }

    @Test
    void anEmptyAnswerIsOnlyDoneWithZeroFragments() {
        StreamChatUseCase useCase = useCaseAnswering(Flux.empty());

        StepVerifier.create(useCase.stream(command("Hola")))
                .expectNext(new DoneEvent(0))
                .verifyComplete();
    }

    @Test
    void recordsTheFirstTokenOnceAndTheCompletedResponse() {
        StreamChatUseCase useCase = useCaseAnswering(Flux.just("a", "b", "c"));

        StepVerifier.create(useCase.stream(command("Hola"))).expectNextCount(4).verifyComplete();

        verify(metrics, times(1)).recordFirstToken(any(Duration.class));
        verify(metrics, times(1)).recordResponse(eq(ChatOutcome.COMPLETED), any(Duration.class));
    }

    @Test
    void withoutFragmentsThereIsNoFirstTokenButTheResponseIsStillRecorded() {
        StreamChatUseCase useCase = useCaseAnswering(Flux.empty());

        StepVerifier.create(useCase.stream(command("Hola"))).expectNextCount(1).verifyComplete();

        verify(metrics, never()).recordFirstToken(any(Duration.class));
        verify(metrics, times(1)).recordResponse(eq(ChatOutcome.COMPLETED), any(Duration.class));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\n\t "})
    void rejectsAMissingOrBlankMessageWithoutCallingTheModel(String message) {
        ChatModelPort model = mock(ChatModelPort.class);
        StreamChatUseCase useCase = newUseCase(model);

        StepVerifier.create(useCase.stream(command(message)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.INVALID_CHAT_MESSAGE))
                .verify();

        verifyNoInteractions(model);
    }

    @Test
    void rejectsAMessageOverTheLimitWithoutCallingTheModel() {
        ChatModelPort model = mock(ChatModelPort.class);
        StreamChatUseCase useCase = newUseCase(model);

        StepVerifier.create(useCase.stream(command("x".repeat(MAX_LENGTH + 1))))
                .expectError(BusinessException.class)
                .verify();

        verifyNoInteractions(model);
        verifyNoInteractions(knowledge);
    }

    @Test
    void acceptsAMessageOfExactlyTheLimit() {
        StreamChatUseCase useCase = useCaseAnswering(Flux.just("ok"));

        StepVerifier.create(useCase.stream(command("x".repeat(MAX_LENGTH))))
                .expectNext(new TokenEvent("ok"), new DoneEvent(1))
                .verifyComplete();
    }

    @Test
    void aFailureBeforeTheFirstFragmentIsAnErrorOfTheFluxAndIsRecordedAsError() {
        StreamChatUseCase useCase = useCaseAnswering(Flux.error(
                ExceptionUtil.technical(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE, new IllegalStateException("down"))));

        StepVerifier.create(useCase.stream(command("Hola")))
                .expectErrorSatisfies(error -> assertThat(error).hasMessage("The chat model is not available"))
                .verify();

        verify(metrics).recordResponse(eq(ChatOutcome.ERROR), any(Duration.class));
        verify(metrics, never()).recordFirstToken(any(Duration.class));
    }

    @Test
    void aFailureAfterSomeFragmentsBecomesAFinalErrorEvent() {
        Flux<String> answer = Flux.concat(Flux.just("uno", "dos"), Flux.error(new IllegalStateException("boom")));
        StreamChatUseCase useCase = useCaseAnswering(answer);

        StepVerifier.create(useCase.stream(command("Hola")))
                .expectNext(new TokenEvent("uno"), new TokenEvent("dos"))
                .expectNext(new ErrorEvent(ResponseCodeEnum.CHAT_STREAM_FAILED.getCode(),
                        ResponseCodeEnum.CHAT_STREAM_FAILED.getDefaultMessage()))
                .verifyComplete();

        verify(metrics).recordResponse(eq(ChatOutcome.ERROR), any(Duration.class));
        verify(metrics, never()).recordResponse(eq(ChatOutcome.COMPLETED), any(Duration.class));
    }

    @Test
    void theErrorEventKeepsTheCodeOfATypedFailure() {
        Flux<String> answer = Flux.concat(Flux.just("uno"), Flux.error(
                ExceptionUtil.technical(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE, null)));
        StreamChatUseCase useCase = useCaseAnswering(answer);

        StepVerifier.create(useCase.stream(command("Hola")))
                .expectNext(new TokenEvent("uno"))
                .expectNext(new ErrorEvent(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE.getCode(),
                        ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE.getDefaultMessage()))
                .verifyComplete();
    }

    @Test
    void aModelFailureBeforeTheFirstFragmentIsModelUnavailable() {
        StreamChatUseCase useCase = useCaseAnswering(Flux.error(new ChatModelException(new IllegalStateException())));

        StepVerifier.create(useCase.stream(command("Hola")))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(ChatModelException.class)
                        .extracting(e -> ((ChatModelException) e).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE))
                .verify();
    }

    @Test
    void aModelFailureAfterSomeFragmentsIsAStreamFailedEvent() {
        Flux<String> answer = Flux.concat(Flux.just("uno"), Flux.error(new ChatModelException(null)));
        StreamChatUseCase useCase = useCaseAnswering(answer);

        StepVerifier.create(useCase.stream(command("Hola")))
                .expectNext(new TokenEvent("uno"))
                .expectNext(new ErrorEvent(ResponseCodeEnum.CHAT_STREAM_FAILED.getCode(),
                        ResponseCodeEnum.CHAT_STREAM_FAILED.getDefaultMessage()))
                .verifyComplete();
    }

    @Test
    void logsCarryTheRequestIdButNeverTheCustomerText(CapturedOutput output) {
        ChatModelPort model = mock(ChatModelPort.class);
        when(model.streamAnswer(any(), any()))
                .thenReturn(Flux.concat(Flux.just("x"), Flux.error(new IllegalStateException())));
        StreamChatUseCase useCase = newUseCase(model);

        StepVerifier.create(useCase.stream(new StreamChatCommand("my-secret-order-complaint", "req-77")))
                .expectNextCount(2)
                .verifyComplete();

        assertThat(output.getOut()).contains("req-77").doesNotContain("my-secret-order-complaint");
    }

    @Test
    void cancellingTheStreamCancelsTheModelAndRecordsOneCancelledResponse() {
        Sinks.Many<String> source = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean modelCancelled = new AtomicBoolean();
        StreamChatUseCase useCase = useCaseAnswering(source.asFlux().doOnCancel(() -> modelCancelled.set(true)));

        StepVerifier.create(useCase.stream(command("Hola")))
                .then(() -> source.tryEmitNext("uno"))
                .expectNext(new TokenEvent("uno"))
                .thenCancel()
                .verify();

        assertThat(modelCancelled).isTrue();
        verify(metrics, times(1)).recordResponse(eq(ChatOutcome.CANCELLED), any(Duration.class));
        verify(metrics, never()).recordResponse(eq(ChatOutcome.COMPLETED), any(Duration.class));
        verify(metrics, never()).recordResponse(eq(ChatOutcome.ERROR), any(Duration.class));
    }

    @Test
    void cancellingLeavesTheModelWithoutSubscribersSoNoOrphanFragmentsAreProduced() {
        TestPublisher<String> model = TestPublisher.create();
        StreamChatUseCase useCase = useCaseAnswering(model.flux());

        StepVerifier.create(useCase.stream(command("Hola")))
                .then(() -> model.next("uno"))
                .expectNext(new TokenEvent("uno"))
                .thenCancel()
                .verify();

        model.assertCancelled();
        model.assertNoSubscribers();
    }

    @Test
    void aStreamThatAlreadyCompletedIsNotRecordedAsCancelled() {
        StreamChatUseCase useCase = useCaseAnswering(Flux.just("a"));

        StepVerifier.create(useCase.stream(command("Hola"))).expectNextCount(2).verifyComplete();

        verify(metrics, never()).recordResponse(eq(ChatOutcome.CANCELLED), any(Duration.class));
    }

    // --- retrieval-augmented answers (LAB-22) ---

    @Test
    void asksTheKnowledgeBaseForTopKCandidatesAndHandsTheSelectedOnesToTheModel() {
        ChatModelPort model = mock(ChatModelPort.class);
        List<ContextFragment> candidates = List.of(fragment("Seven days to return.", 0.9),
                fragment("Refunds take ten days.", 0.8));
        when(knowledge.retrieve("¿Devoluciones?", 4)).thenReturn(Mono.just(candidates));
        when(model.streamAnswer(any(), any())).thenReturn(Flux.just("ok"));

        StepVerifier.create(useCaseWith(model).stream(command("¿Devoluciones?")))
                .expectNext(new TokenEvent("ok"), new DoneEvent(1))
                .verifyComplete();

        verify(model).streamAnswer("¿Devoluciones?", candidates);
        verify(metrics).recordContext(eq(ContextOutcome.WITH_CONTEXT), eq(2), any(Duration.class));
    }

    private StreamChatUseCase useCaseWith(ChatModelPort model) {
        return newUseCase(model);
    }

    @Test
    void eachQuestionRetrievesItsOwnContextFromItsOwnText() {
        ChatModelPort model = mock(ChatModelPort.class);
        when(model.streamAnswer(any(), any())).thenReturn(Flux.just("ok"));
        StreamChatUseCase useCase = newUseCase(model);

        StepVerifier.create(useCase.stream(command("first question"))).expectNextCount(2).verifyComplete();
        StepVerifier.create(useCase.stream(command("second question"))).expectNextCount(2).verifyComplete();

        verify(knowledge, times(1)).retrieve("first question", 4);
        verify(knowledge, times(1)).retrieve("second question", 4);
    }

    @Test
    void usesTheConfiguredTopKAndDropsFragmentsBelowTheThreshold() {
        ChatModelPort model = mock(ChatModelPort.class);
        ContextFragment close = fragment("Close fragment.", 0.8);
        when(knowledge.retrieve("q", 2)).thenReturn(Mono.just(List.of(close, fragment("Far fragment.", 0.3))));
        when(model.streamAnswer(any(), any())).thenReturn(Flux.just("ok"));
        StreamChatUseCase useCase = newUseCase(model, new RagProperties(2, 0.6, null, null, null));

        StepVerifier.create(useCase.stream(command("q"))).expectNextCount(2).verifyComplete();

        verify(model).streamAnswer("q", List.of(close));
    }

    @Test
    void withoutCandidatesTheModelIsStillCalledWithEmptyContext() {
        ChatModelPort model = mock(ChatModelPort.class);
        when(model.streamAnswer(any(), any())).thenReturn(Flux.just("no info"));

        StepVerifier.create(newUseCase(model).stream(command("¿Y esto?")))
                .expectNext(new TokenEvent("no info"), new DoneEvent(1))
                .verifyComplete();

        verify(model).streamAnswer("¿Y esto?", List.of());
        verify(metrics).recordContext(eq(ContextOutcome.NO_CONTEXT), eq(0), any(Duration.class));
    }

    @Test
    void whenNoFragmentReachesTheThresholdTheContextIsEmpty() {
        ChatModelPort model = mock(ChatModelPort.class);
        when(knowledge.retrieve("q", 4)).thenReturn(Mono.just(List.of(fragment("Weak match.", 0.2))));
        when(model.streamAnswer(any(), any())).thenReturn(Flux.just("ok"));

        StepVerifier.create(newUseCase(model, new RagProperties(null, 0.6, null, null, null)).stream(command("q")))
                .expectNextCount(2)
                .verifyComplete();

        verify(model).streamAnswer("q", List.of());
        verify(metrics).recordContext(eq(ContextOutcome.NO_CONTEXT), eq(0), any(Duration.class));
    }

    @Test
    void aRetrievalFailureIsTheContextUnavailableErrorAndTheModelIsNeverCalled() {
        ChatModelPort model = mock(ChatModelPort.class);
        when(knowledge.retrieve(any(), anyInt()))
                .thenReturn(Mono.error(new KnowledgeRetrievalException(new IllegalStateException("db down"))));

        StepVerifier.create(newUseCase(model).stream(command("q")))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(KnowledgeRetrievalException.class)
                        .extracting(e -> ((KnowledgeRetrievalException) e).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.CHAT_CONTEXT_UNAVAILABLE))
                .verify();

        verifyNoInteractions(model);
        verify(metrics).recordContext(eq(ContextOutcome.FAILED), eq(0), any(Duration.class));
        verify(metrics).recordResponse(eq(ChatOutcome.ERROR), any(Duration.class));
    }

    @Test
    void cancellingWhileTheContextIsBeingRetrievedRecordsNoContextFailure() {
        ChatModelPort model = mock(ChatModelPort.class);
        when(knowledge.retrieve(any(), anyInt())).thenReturn(Mono.never());

        StepVerifier.create(newUseCase(model).stream(command("q"))).thenCancel().verify();

        verify(metrics, never()).recordContext(any(), anyInt(), any(Duration.class));
        verifyNoInteractions(model);
    }

    @Test
    void contextLogsNeverCarryTheQuestionOrTheFragmentText(CapturedOutput output) {
        ChatModelPort model = mock(ChatModelPort.class);
        when(knowledge.retrieve(any(), anyInt()))
                .thenReturn(Mono.just(List.of(fragment("secret-policy-text", 0.9))));
        when(model.streamAnswer(any(), any())).thenReturn(Flux.just("ok"));

        StepVerifier.create(newUseCase(model).stream(new StreamChatCommand("secret-customer-question", "req-9")))
                .expectNextCount(2)
                .verifyComplete();

        assertThat(output.getOut()).contains("req-9").doesNotContain("secret-customer-question")
                .doesNotContain("secret-policy-text");
    }
}
