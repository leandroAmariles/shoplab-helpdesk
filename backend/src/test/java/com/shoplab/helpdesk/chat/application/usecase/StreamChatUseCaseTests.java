package com.shoplab.helpdesk.chat.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.application.port.out.ChatModelPort;
import com.shoplab.helpdesk.chat.config.ChatProperties;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.ErrorEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.enums.ChatOutcome;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(OutputCaptureExtension.class)
class StreamChatUseCaseTests {

    private static final int MAX_LENGTH = 4000;

    private final ChatMetricsPort metrics = mock(ChatMetricsPort.class);

    private StreamChatUseCase useCaseAnswering(Flux<String> answer) {
        ChatModelPort model = message -> answer;
        return new StreamChatUseCase(model, metrics, new ChatProperties(MAX_LENGTH, "system"));
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
        StreamChatUseCase useCase = new StreamChatUseCase(model, metrics, new ChatProperties(MAX_LENGTH, "s"));

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
        StreamChatUseCase useCase = new StreamChatUseCase(model, metrics, new ChatProperties(MAX_LENGTH, "s"));

        StepVerifier.create(useCase.stream(command("x".repeat(MAX_LENGTH + 1))))
                .expectError(BusinessException.class)
                .verify();

        verifyNoInteractions(model);
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
    void logsCarryTheRequestIdButNeverTheCustomerText(CapturedOutput output) {
        ChatModelPort model = mock(ChatModelPort.class);
        when(model.streamAnswer(any())).thenReturn(Flux.concat(Flux.just("x"), Flux.error(new IllegalStateException())));
        StreamChatUseCase useCase = new StreamChatUseCase(model, metrics, new ChatProperties(MAX_LENGTH, "s"));

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
    void aStreamThatAlreadyCompletedIsNotRecordedAsCancelled() {
        StreamChatUseCase useCase = useCaseAnswering(Flux.just("a"));

        StepVerifier.create(useCase.stream(command("Hola"))).expectNextCount(2).verifyComplete();

        verify(metrics, never()).recordResponse(eq(ChatOutcome.CANCELLED), any(Duration.class));
    }
}
