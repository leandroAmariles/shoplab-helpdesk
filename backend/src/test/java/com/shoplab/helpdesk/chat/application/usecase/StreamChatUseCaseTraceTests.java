package com.shoplab.helpdesk.chat.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.application.port.out.ChatModelPort;
import com.shoplab.helpdesk.chat.application.port.out.ChatTracePort;
import com.shoplab.helpdesk.chat.application.port.out.ConversationMemoryPort;
import com.shoplab.helpdesk.chat.application.port.out.GenerationScope;
import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.application.port.out.TurnTrace;
import com.shoplab.helpdesk.chat.config.ChatProperties;
import com.shoplab.helpdesk.chat.config.MemoryProperties;
import com.shoplab.helpdesk.chat.config.RagProperties;
import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.SourcesEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.PromptMessage;
import com.shoplab.helpdesk.chat.domain.model.RetrievalTrace;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.ToolCallTrace;
import com.shoplab.helpdesk.chat.domain.model.enums.NoContextMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The use case opens the trace of the turn and reports what only it knows (specs/LAB-64, US2). */
class StreamChatUseCaseTraceTests {

    private static final String CONVERSATION = "0b0e6c8e-5a4f-4d58-9d44-0f7c3d1d4a11";

    private final ChatMetricsPort metrics = mock(ChatMetricsPort.class);
    private final KnowledgeContextPort knowledge = mock(KnowledgeContextPort.class);
    private final ConversationMemoryPort memory = mock(ConversationMemoryPort.class);
    private final ChatModelPort model = mock(ChatModelPort.class);
    private final RecordingTrace trace = new RecordingTrace();

    {
        when(knowledge.retrieve(any(), anyInt())).thenReturn(Mono.just(List.of()));
        when(memory.recent(any(), anyInt())).thenReturn(Mono.just(List.of()));
        when(memory.saveTurn(any(), any())).thenReturn(Mono.empty());
        when(model.streamAnswer(any(), any(), any())).thenReturn(Flux.just("Tarda ", "diez días"));
    }

    private StreamChatUseCase useCase(NoContextMode mode) {
        ConversationHistory history = new ConversationHistory(memory, new HistoryWindow(), new FollowUpQuery(),
                new MemoryProperties(null, null, null), metrics);
        return new StreamChatUseCase(model, knowledge, new ContextSelector(), history, metrics,
                new ChatProperties(4000, "system"),
                new RagProperties(null, null, null, null, null, mode, null), trace);
    }

    private static ContextFragment fragment(String text, double score) {
        return new ContextFragment("returns.md", "Returns", "Plazos", 0, text, score);
    }

    @Test
    void theTurnIsOpenedWithTheCommandItReceived() {
        StreamChatCommand command = new StreamChatCommand("¿Plazo?", "req-9", CONVERSATION);

        StepVerifier.create(useCase(NoContextMode.MODEL).stream(command)).expectNextCount(3).verifyComplete();

        assertThat(trace.opened).containsExactly(command);
    }

    @Test
    void theRetrievalIsReportedWithWhatWasHandedToTheModelAndItsScores() {
        when(knowledge.retrieve(any(), anyInt())).thenReturn(Mono.just(List.of(
                fragment("Diez días.", 0.92), fragment("Cinco días.", 0.81), fragment("Irrelevante.", 0.2))));

        StepVerifier.create(useCase(NoContextMode.MODEL).stream(new StreamChatCommand("¿Plazo?", "req-1")))
                .expectNextCount(4).verifyComplete();

        assertThat(trace.retrievals).hasSize(1);
        RetrievalTrace retrieval = trace.retrievals.get(0);
        assertThat(retrieval.searchText()).isEqualTo("¿Plazo?");
        assertThat(retrieval.candidates()).isEqualTo(3);
        assertThat(retrieval.delivered()).extracting(ContextFragment::score).containsExactly(0.92, 0.81);
        assertThat(retrieval.delivered()).extracting(ContextFragment::text).containsExactly("Diez días.",
                "Cinco días.");
        assertThat(retrieval.failed()).isFalse();
    }

    @Test
    void theSearchTextIncludesThePreviousQuestionOfAFollowUp() {
        when(memory.recent(any(), anyInt())).thenReturn(Mono.just(List.of(
                new ConversationTurn("¿Cuánto tarda el envío?", "Tres días [1]."))));

        StepVerifier.create(useCase(NoContextMode.MODEL)
                .stream(new StreamChatCommand("¿y si es urgente?", "req-1", CONVERSATION)))
                .expectNextCount(3).verifyComplete();

        assertThat(trace.retrievals.get(0).searchText()).contains("¿Cuánto tarda el envío?")
                .contains("¿y si es urgente?");
    }

    @Test
    void theTurnsOfTheHistoryThatWentIntoThePromptAreReported() {
        when(memory.recent(any(), anyInt())).thenReturn(Mono.just(List.of(
                new ConversationTurn("q1", "a1"), new ConversationTurn("q2", "a2"))));

        StepVerifier.create(useCase(NoContextMode.MODEL)
                .stream(new StreamChatCommand("q3", "req-1", CONVERSATION)))
                .expectNextCount(3).verifyComplete();

        assertThat(trace.historyTurns).containsExactly(2);
    }

    @Test
    void withoutConversationTheHistoryIsZeroTurns() {
        StepVerifier.create(useCase(NoContextMode.MODEL).stream(new StreamChatCommand("q", "req-1")))
                .expectNextCount(3).verifyComplete();

        assertThat(trace.historyTurns).containsExactly(0);
    }

    @Test
    void withoutContextAndAFixedMessageTheModelIsNotCalledAndTheTraceSaysSo() {
        StepVerifier.create(useCase(NoContextMode.FIXED_MESSAGE).stream(new StreamChatCommand("q", "req-1")))
                .expectNextCount(2).verifyComplete();

        assertThat(trace.fixedMessageAnswers).isEqualTo(1);
        assertThat(trace.retrievals.get(0).delivered()).isEmpty();
        verifyNoInteractions(model);
    }

    @Test
    void withoutContextAndTheModelAnsweringNothingIsReportedAsAFixedMessage() {
        StepVerifier.create(useCase(NoContextMode.MODEL).stream(new StreamChatCommand("q", "req-1")))
                .expectNextCount(3).verifyComplete();

        assertThat(trace.fixedMessageAnswers).isZero();
    }

    @Test
    void aRejectedMessageNeverOpensATurn() {
        StepVerifier.create(useCase(NoContextMode.MODEL).stream(new StreamChatCommand(" ", "req-1")))
                .expectError().verify();

        assertThat(trace.opened).isEmpty();
    }

    @Test
    void theEventsAreTheOnesTheTurnProduces() {
        when(knowledge.retrieve(any(), anyInt())).thenReturn(Mono.just(List.of(fragment("Diez días.", 0.9))));

        List<ChatEvent> events = useCase(NoContextMode.MODEL)
                .stream(new StreamChatCommand("¿Plazo?", "req-1")).collectList().block();

        assertThat(events).hasSize(4);
        assertThat(events.get(0)).isInstanceOf(SourcesEvent.class);
        assertThat(events.get(1)).isEqualTo(new TokenEvent("Tarda "));
        assertThat(events.get(3)).isEqualTo(new DoneEvent(2));
    }

    @Test
    void aFailingSearchReportsAFailedRetrievalBeforeTheErrorTravelsOn() {
        when(knowledge.retrieve(any(), anyInt())).thenReturn(Mono.error(new KnowledgeRetrievalException(
                new IllegalStateException("db down"))));

        StepVerifier.create(useCase(NoContextMode.MODEL).stream(new StreamChatCommand("q", "req-1")))
                .expectError(KnowledgeRetrievalException.class).verify();

        assertThat(trace.retrievals).hasSize(1);
        assertThat(trace.retrievals.get(0).failed()).isTrue();
        assertThat(trace.retrievals.get(0).delivered()).isEmpty();
    }

    /** Remembers what the use case reports and runs the turn as it is. */
    private static final class RecordingTrace implements ChatTracePort, TurnTrace {

        private final List<StreamChatCommand> opened = new ArrayList<>();
        private final List<RetrievalTrace> retrievals = new ArrayList<>();
        private final List<Integer> historyTurns = new ArrayList<>();
        private int fixedMessageAnswers;

        @Override
        public Flux<ChatEvent> traceTurn(StreamChatCommand command, Function<TurnTrace, Flux<ChatEvent>> turn) {
            return Flux.defer(() -> {
                opened.add(command);
                return turn.apply(this);
            });
        }

        @Override
        public void historyTurns(int turns) {
            historyTurns.add(turns);
        }

        @Override
        public void retrieval(RetrievalTrace retrieval, Duration elapsed) {
            retrievals.add(retrieval);
        }

        @Override
        public void fixedMessageAnswer() {
            fixedMessageAnswers++;
        }

        @Override
        public GenerationScope startGeneration(String model, Map<String, Object> parameters,
                List<PromptMessage> messages) {
            return GenerationScope.NONE;
        }

        @Override
        public void toolCall(ToolCallTrace call, Duration elapsed) {
            // not reported by the use case
        }
    }
}
