package com.shoplab.helpdesk.chat.application.usecase;

import static com.shoplab.helpdesk.chat.support.SmallTalkTestSupport.smallTalk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.application.port.out.ChatModelPort;
import com.shoplab.helpdesk.chat.application.port.out.ChatTracePort;
import com.shoplab.helpdesk.chat.application.port.out.ConversationMemoryPort;
import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.application.port.out.TurnTrace;
import com.shoplab.helpdesk.chat.config.ChatProperties;
import com.shoplab.helpdesk.chat.config.MemoryProperties;
import com.shoplab.helpdesk.chat.config.RagProperties;
import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.enums.ContextOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.NoContextMode;
import com.shoplab.helpdesk.chat.domain.model.enums.TurnKind;
import com.shoplab.helpdesk.chat.infrastructure.out.metrics.MicrometerChatMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Every attended turn is counted in exactly one kind; a failed retrieval has none (LAB-114, FR-016, SC-006). */
@ExtendWith(OutputCaptureExtension.class)
class StreamChatUseCaseTurnKindTests {

    private final ChatMetricsPort metrics = mock(ChatMetricsPort.class);
    private final KnowledgeContextPort knowledge = mock(KnowledgeContextPort.class);
    private final ChatModelPort chatModel = mock(ChatModelPort.class);
    private final TurnTrace trace = mock(TurnTrace.class);

    private StreamChatUseCase useCase(ChatMetricsPort usedMetrics, NoContextMode mode) {
        SmallTalk smallTalk = smallTalk(true, message -> Flux.just("Respuesta-secreta"));
        ConversationHistory history = new ConversationHistory(mock(ConversationMemoryPort.class), new HistoryWindow(),
                new FollowUpQuery(), new MemoryProperties(null, null, null), usedMetrics, smallTalk);
        ChatTracePort chatTrace = (command, turn) -> turn.apply(trace);
        return new StreamChatUseCase(chatModel, knowledge, new ContextSelector(), history, usedMetrics,
                new ChatProperties(4000, "system"), new RagProperties(null, null, null, null, null, mode, null),
                chatTrace, smallTalk);
    }

    private static StreamChatCommand command(String message) {
        return new StreamChatCommand(message, "req-1");
    }

    private void context(boolean found) {
        when(knowledge.retrieve(any(), anyInt())).thenReturn(Mono.just(found
                ? List.of(new ContextFragment("shipping.md", "Envíos", "Plazos", 0, "Tarda 3 días.", 0.9))
                : List.of()));
        when(chatModel.streamAnswer(any(), any(), any())).thenReturn(Flux.just("Respuesta"));
    }

    private void drain(StreamChatUseCase useCase, String message) {
        useCase.stream(command(message)).onErrorResume(error -> Flux.empty()).collectList().block();
    }

    @Test
    void aCourtesyIsCountedAsSmallTalkAndMarksTheTraceWithoutRetrievalOrFirstToken() {
        drain(useCase(metrics, NoContextMode.FIXED_MESSAGE), "hola");

        verify(metrics, times(1)).recordTurn(TurnKind.SMALL_TALK);
        verify(metrics, never()).recordTurn(TurnKind.WITH_CONTEXT);
        verify(metrics, never()).recordTurn(TurnKind.NO_CONTEXT);
        verify(metrics, never()).recordFirstToken(any(Duration.class));
        verify(metrics, never()).recordContext(any(), anyInt(), any(Duration.class));
        verify(trace).smallTalkAnswer();
        verify(trace, never()).retrieval(any(), any(Duration.class));
    }

    @Test
    void aQuestionWithFragmentsIsCountedAsWithContext() {
        context(true);

        drain(useCase(metrics, NoContextMode.FIXED_MESSAGE), "¿cuánto tarda un envío?");

        verify(metrics, times(1)).recordTurn(TurnKind.WITH_CONTEXT);
        verify(metrics, never()).recordTurn(TurnKind.SMALL_TALK);
        verify(trace, never()).smallTalkAnswer();
    }

    @Test
    void aQuestionWithoutContextIsCountedAsNoContextWhateverTheMode() {
        context(false);

        drain(useCase(metrics, NoContextMode.FIXED_MESSAGE), "¿cuánto tarda un envío a la luna?");
        drain(useCase(metrics, NoContextMode.MODEL), "¿cuánto tarda un envío a la luna?");

        verify(metrics, times(2)).recordTurn(TurnKind.NO_CONTEXT);
    }

    @Test
    void aFailedRetrievalHasNoKindAndIsCountedAsAFailedRetrieval() {
        when(knowledge.retrieve(any(), anyInt())).thenReturn(Mono.error(new KnowledgeRetrievalException(
                new IllegalStateException("down"))));

        drain(useCase(metrics, NoContextMode.FIXED_MESSAGE), "¿cuánto tarda un envío?");

        verify(metrics, never()).recordTurn(any());
        verify(metrics).recordContext(any(ContextOutcome.class), anyInt(), any(Duration.class));
    }

    @Test
    void anInvalidMessageIsNotATurn() {
        drain(useCase(metrics, NoContextMode.FIXED_MESSAGE), "   ");

        verify(metrics, never()).recordTurn(any());
    }

    @Test
    void kindsPlusFailedRetrievalsAddUpToTheValidTurns() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerChatMetrics real = new MicrometerChatMetrics(registry);
        StreamChatUseCase useCase = useCase(real, NoContextMode.FIXED_MESSAGE);
        context(true);
        drain(useCase, "hola");
        drain(useCase, "gracias");
        drain(useCase, "¿cuánto tarda un envío?");
        context(false);
        drain(useCase, "¿cuánto tarda un envío a la luna?");
        when(knowledge.retrieve(any(), anyInt())).thenReturn(Mono.error(new KnowledgeRetrievalException(
                new IllegalStateException("down"))));
        drain(useCase, "¿cuánto tarda un envío?");
        drain(useCase, "  ");

        double smallTalk = registry.get("shoplab.chat.turns").tag("kind", "small_talk").counter().count();
        double withContext = registry.get("shoplab.chat.turns").tag("kind", "with_context").counter().count();
        double noContext = registry.get("shoplab.chat.turns").tag("kind", "no_context").counter().count();
        long failed = registry.get("shoplab.chat.context.retrieval").tag("outcome", "failed").timer().count();

        assertThat(smallTalk).isEqualTo(2);
        assertThat(withContext).isEqualTo(1);
        assertThat(noContext).isEqualTo(1);
        assertThat(failed).isEqualTo(1);
        assertThat(smallTalk + withContext + noContext + failed).isEqualTo(5);
    }

    @Test
    void theLogOfASmallTalkTurnCarriesTheRequestAndTheLengthNeverTheTexts(CapturedOutput output) {
        StepVerifier.create(useCase(metrics, NoContextMode.FIXED_MESSAGE).stream(command("buenas tardes")))
                .expectNextCount(2).verifyComplete();

        assertThat(output.getAll()).contains("Small talk turn: requestId=req-1, messageLength=13")
                .doesNotContain("buenas tardes").doesNotContain("Respuesta-secreta");
    }
}
