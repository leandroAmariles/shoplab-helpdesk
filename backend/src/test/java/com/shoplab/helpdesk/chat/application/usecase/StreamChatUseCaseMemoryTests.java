package com.shoplab.helpdesk.chat.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.application.port.out.ChatModelPort;
import com.shoplab.helpdesk.chat.application.port.out.ConversationMemoryPort;
import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.config.ChatProperties;
import com.shoplab.helpdesk.chat.config.MemoryProperties;
import com.shoplab.helpdesk.chat.config.RagProperties;
import com.shoplab.helpdesk.chat.domain.exception.ChatModelException;
import com.shoplab.helpdesk.chat.domain.exception.ConversationMemoryException;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.ErrorEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.ConversationId;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.enums.MemoryOperation;
import com.shoplab.helpdesk.chat.domain.model.enums.NoContextMode;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** The memory of the conversation in the chat flow (specs/LAB-24): history in, the finished turn out. */
@ExtendWith(OutputCaptureExtension.class)
class StreamChatUseCaseMemoryTests {

    private static final String ID = "3f1b6c52-8d0e-4c1a-9a53-7b2a0c9d4e11";
    private static final String OTHER_ID = "9a7d1f40-2b6c-4e8d-8c31-5f0e7a6b1d22";
    private static final String QUESTION = "¿cuánto tarda el envío estándar?";
    private static final String FOLLOW_UP = "¿y si es internacional?";

    private final ChatMetricsPort metrics = mock(ChatMetricsPort.class);
    private final KnowledgeContextPort knowledge = mock(KnowledgeContextPort.class);
    private final ConversationMemoryPort memory = mock(ConversationMemoryPort.class);
    private final ChatModelPort model = mock(ChatModelPort.class);

    {
        when(knowledge.retrieve(any(), anyInt())).thenReturn(Mono.just(List.of()));
        when(memory.recent(any(), anyInt())).thenReturn(Mono.just(List.of()));
        when(memory.saveTurn(any(), any())).thenReturn(Mono.empty());
        when(model.streamAnswer(any(), any(), any())).thenReturn(Flux.just("Tarda ", "diez días"));
    }

    private StreamChatUseCase useCase() {
        return useCase(new MemoryProperties(null, null, null), NoContextMode.MODEL);
    }

    private StreamChatUseCase useCase(MemoryProperties properties, NoContextMode mode) {
        ConversationHistory history = new ConversationHistory(memory, new HistoryWindow(), new FollowUpQuery(),
                properties, metrics);
        return new StreamChatUseCase(model, knowledge, new ContextSelector(), history, metrics,
                new ChatProperties(4000, "system"), new RagProperties(null, null, null, null, null, mode, null));
    }

    private static StreamChatCommand command(String message, String conversationId) {
        return new StreamChatCommand(message, "req-1", conversationId);
    }

    private static ConversationId id(String value) {
        return new ConversationId(value);
    }

    private static ConversationTurn turn(String question, String answer) {
        return new ConversationTurn(question, answer);
    }

    private static List<ConversationTurn> turns(int count, int textLength) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(number -> turn("q" + number + " " + "x".repeat(textLength),
                        "a" + number + " " + "y".repeat(textLength)))
                .toList();
    }

    private void remembering(List<ConversationTurn> history) {
        when(memory.recent(any(), anyInt())).thenReturn(Mono.just(history));
    }

    // --- US1: a follow-up is understood ---

    @Test
    void aFollowUpSearchesWithThePreviousQuestionAndTheModelGetsTheHistoryWithoutCitationMarks() {
        remembering(List.of(turn(QUESTION, "Tarda tres días [1].")));

        StepVerifier.create(useCase().stream(command(FOLLOW_UP, ID))).expectNextCount(3).verifyComplete();

        verify(knowledge).retrieve(QUESTION + "\n" + FOLLOW_UP, 4);
        verify(model).streamAnswer(FOLLOW_UP, List.of(turn(QUESTION, "Tarda tres días.")), List.of());
    }

    @Test
    void theFirstQuestionOfAConversationIsAnsweredLikeBefore() {
        StepVerifier.create(useCase().stream(command(QUESTION, ID))).expectNextCount(3).verifyComplete();

        verify(knowledge).retrieve(QUESTION, 4);
        verify(model).streamAnswer(QUESTION, List.of(), List.of());
    }

    @Test
    void theTurnIsStoredWithTheOriginalQuestionAndTheWholeAnswerBeforeDone() {
        AtomicBoolean stored = new AtomicBoolean();
        AtomicBoolean storedWhenDoneArrived = new AtomicBoolean();
        when(memory.saveTurn(any(), any())).thenReturn(Mono.fromRunnable(() -> stored.set(true)));

        StepVerifier.create(useCase().stream(command(FOLLOW_UP, ID))
                        .doOnNext(event -> {
                            if (event instanceof DoneEvent) {
                                storedWhenDoneArrived.set(stored.get());
                            }
                        }))
                .expectNext(new TokenEvent("Tarda "), new TokenEvent("diez días"), new DoneEvent(2))
                .verifyComplete();

        assertThat(storedWhenDoneArrived).isTrue();
        verify(memory).saveTurn(id(ID), turn(FOLLOW_UP, "Tarda diez días"));
    }

    @Test
    void theFixedNoInformationAnswerIsAlsoPartOfTheConversation() {
        StreamChatUseCase fixed = useCase(new MemoryProperties(null, null, null), NoContextMode.FIXED_MESSAGE);

        StepVerifier.create(fixed.stream(command("¿precio de lo que sea?", ID))).expectNextCount(2).verifyComplete();

        verify(memory).saveTurn(eq(id(ID)), any(ConversationTurn.class));
        verifyNoInteractions(model);
    }

    @Test
    void anAnswerWithoutTextIsNotATurn() {
        when(model.streamAnswer(any(), any(), any())).thenReturn(Flux.empty());

        StepVerifier.create(useCase().stream(command(QUESTION, ID))).expectNext(new DoneEvent(0)).verifyComplete();

        verify(memory, never()).saveTurn(any(), any());
    }

    @Test
    void theSearchOnlyUsesTheMessageWhenRetrievalTurnsAreZero() {
        remembering(List.of(turn(QUESTION, "Tarda tres días.")));

        StepVerifier.create(useCase(new MemoryProperties(null, null, 0), NoContextMode.MODEL)
                .stream(command(FOLLOW_UP, ID))).expectNextCount(3).verifyComplete();

        verify(knowledge).retrieve(FOLLOW_UP, 4);
        verify(model).streamAnswer(eq(FOLLOW_UP), any(), any());
    }

    // --- US2: identifier, isolation, failures ---

    @Test
    void withoutAConversationIdNothingIsReadOrStored() {
        StepVerifier.create(useCase().stream(new StreamChatCommand(QUESTION, "req-1"))).expectNextCount(3)
                .verifyComplete();

        verifyNoInteractions(memory);
        verify(knowledge).retrieve(QUESTION, 4);
    }

    @Test
    void withTheMemoryTurnedOffAConversationIdIsValidatedButNeverUsed() {
        StreamChatUseCase off = useCase(new MemoryProperties(0, null, null), NoContextMode.MODEL);

        StepVerifier.create(off.stream(command(QUESTION, ID))).expectNextCount(3).verifyComplete();
        StepVerifier.create(off.stream(command(QUESTION, "no-es-un-uuid")))
                .expectErrorSatisfies(error -> assertThat(((HelpdeskException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.INVALID_CONVERSATION_ID))
                .verify();

        verifyNoInteractions(memory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "no-es-un-uuid", "3f1b6c52-8d0e-4c1a-9a53-7b2a0c9d4e1"})
    void anInvalidConversationIdIsRejectedBeforeAnythingIsCalled(String invalid) {
        StepVerifier.create(useCase().stream(command(QUESTION, invalid)))
                .expectErrorSatisfies(error -> assertThat(((HelpdeskException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.INVALID_CONVERSATION_ID))
                .verify();

        verifyNoInteractions(memory, knowledge, model);
    }

    @Test
    void aMemoryThatCannotBeReadFailsTheRequestBeforeItStartsAndTheModelIsNotCalled() {
        when(memory.recent(any(), anyInt()))
                .thenReturn(Mono.error(new ConversationMemoryException(MemoryOperation.READ, null)));

        StepVerifier.create(useCase().stream(command(FOLLOW_UP, ID)))
                .expectErrorSatisfies(error -> assertThat(((HelpdeskException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.CHAT_MEMORY_UNAVAILABLE))
                .verify();

        verifyNoInteractions(model, knowledge);
        verify(metrics).recordMemoryFailure(MemoryOperation.READ);
    }

    @Test
    void aTurnThatCannotBeStoredEndsWithTheErrorEventInsteadOfDone() {
        when(memory.saveTurn(any(), any()))
                .thenReturn(Mono.error(new ConversationMemoryException(MemoryOperation.WRITE, null)));

        List<ChatEvent> events = new ArrayList<>();
        StepVerifier.create(useCase().stream(command(FOLLOW_UP, ID)).doOnNext(events::add))
                .expectNextCount(3)
                .verifyComplete();

        assertThat(events).containsExactly(new TokenEvent("Tarda "), new TokenEvent("diez días"),
                new ErrorEvent("SLHD_CHAT_00006", "The conversation memory is not available"));
        verify(metrics).recordMemoryFailure(MemoryOperation.WRITE);
    }

    @Test
    void aModelFailureHalfWayStoresNothing() {
        when(model.streamAnswer(any(), any(), any()))
                .thenReturn(Flux.concat(Flux.just("Tarda "), Flux.error(new ChatModelException(null))));

        StepVerifier.create(useCase().stream(command(FOLLOW_UP, ID))).expectNextCount(2).verifyComplete();

        verify(memory, never()).saveTurn(any(), any());
    }

    @Test
    void aModelFailureBeforeTheFirstTokenStoresNothing() {
        when(model.streamAnswer(any(), any(), any())).thenReturn(Flux.error(new ChatModelException(null)));

        StepVerifier.create(useCase().stream(command(FOLLOW_UP, ID))).expectError(ChatModelException.class).verify();

        verify(memory, never()).saveTurn(any(), any());
    }

    @Test
    void aCancelledAnswerStoresNothing() {
        Sinks.Many<String> tokens = Sinks.many().unicast().onBackpressureBuffer();
        when(model.streamAnswer(any(), any(), any())).thenReturn(tokens.asFlux());

        StepVerifier.create(useCase().stream(command(FOLLOW_UP, ID)))
                .then(() -> tokens.tryEmitNext("Tarda "))
                .expectNext(new TokenEvent("Tarda "))
                .thenCancel()
                .verify();

        verify(memory, never()).saveTurn(any(), any());
    }

    @Test
    void eachConversationReadsAndStoresOnlyUnderItsOwnId() {
        StepVerifier.create(useCase().stream(command(QUESTION, ID))).expectNextCount(3).verifyComplete();
        StepVerifier.create(useCase().stream(command(FOLLOW_UP, OTHER_ID.toUpperCase()))).expectNextCount(3)
                .verifyComplete();

        verify(memory).recent(id(ID), 6);
        verify(memory).recent(id(OTHER_ID), 6);
        verify(memory).saveTurn(eq(id(ID)), any());
        verify(memory).saveTurn(eq(id(OTHER_ID)), any());
    }

    // --- US3: the history is bounded ---

    @Test
    void onlyTheLastTurnsWithinTheLimitReachTheModelAndTheTrimIsCounted() {
        remembering(turns(6, 10));

        StepVerifier.create(useCase(new MemoryProperties(5, null, null), NoContextMode.MODEL)
                .stream(command(FOLLOW_UP, ID))).expectNextCount(3).verifyComplete();

        verify(memory).recent(id(ID), 6);
        verify(model).streamAnswer(eq(FOLLOW_UP), argThat(history ->
                history.size() == 5 && history.get(0).question().startsWith("q2")
                        && history.get(4).question().startsWith("q6")), any());
        verify(metrics).recordHistory(10, true);
    }

    @Test
    void theOldestTurnsAreDroppedWholeWhenTheCharactersDoNotFit() {
        remembering(turns(4, 100));

        StepVerifier.create(useCase(new MemoryProperties(5, 450, null), NoContextMode.MODEL)
                .stream(command(FOLLOW_UP, ID))).expectNextCount(3).verifyComplete();

        verify(model).streamAnswer(eq(FOLLOW_UP), argThat(history ->
                history.size() == 2 && history.get(0).question().startsWith("q3")), any());
        verify(metrics).recordHistory(4, true);
    }

    @Test
    void aHistoryWithinTheLimitsIsNotCountedAsTrimmed() {
        remembering(List.of(turn(QUESTION, "Tarda tres días.")));

        StepVerifier.create(useCase().stream(command(FOLLOW_UP, ID))).expectNextCount(3).verifyComplete();

        verify(metrics).recordHistory(2, false);
    }

    // --- US5: nothing of the conversation in the logs ---

    @Test
    void theLogsNeverCarryTheTextsNorTheConversationId(CapturedOutput output) {
        remembering(List.of(turn("pregunta-secreta-previa", "respuesta-secreta-previa")));
        when(model.streamAnswer(any(), any(), any())).thenReturn(Flux.just("respuesta-secreta-nueva"));

        StepVerifier.create(useCase().stream(command("pregunta-secreta-nueva", ID))).expectNextCount(2)
                .verifyComplete();

        assertThat(output.getAll()).doesNotContain("secreta").doesNotContain(ID);
        assertThat(output.getAll()).contains("req-1");
    }

    @Test
    void aMemoryFailureIsLoggedWithTheRequestAndTheOperationButNotTheText(CapturedOutput output) {
        when(memory.recent(any(), anyInt()))
                .thenReturn(Mono.error(new ConversationMemoryException(MemoryOperation.READ, null)));

        StepVerifier.create(useCase().stream(command("pregunta-secreta", ID))).expectError().verify();

        assertThat(output.getAll()).contains("req-1").contains("operation=read")
                .doesNotContain("pregunta-secreta").doesNotContain(ID);
    }
}
