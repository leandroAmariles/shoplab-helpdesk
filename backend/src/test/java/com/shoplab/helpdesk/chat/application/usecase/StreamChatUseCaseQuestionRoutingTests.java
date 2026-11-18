package com.shoplab.helpdesk.chat.application.usecase;

import static com.shoplab.helpdesk.chat.support.SmallTalkTestSupport.smallTalk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.application.port.out.ChatModelPort;
import com.shoplab.helpdesk.chat.application.port.out.ConversationMemoryPort;
import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.application.port.out.SmallTalkModelPort;
import com.shoplab.helpdesk.chat.config.ChatProperties;
import com.shoplab.helpdesk.chat.config.MemoryProperties;
import com.shoplab.helpdesk.chat.config.RagProperties;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.SourcesEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.chat.domain.model.enums.NoContextMode;
import com.shoplab.helpdesk.chat.infrastructure.out.tracing.NoOpChatTrace;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Only a whole courtesy takes the small-talk path: everything else keeps the grounded flow of LAB-22 (US2). */
class StreamChatUseCaseQuestionRoutingTests {

    private final ChatMetricsPort metrics = mock(ChatMetricsPort.class);
    private final KnowledgeContextPort knowledge = mock(KnowledgeContextPort.class);
    private final ConversationMemoryPort memory = mock(ConversationMemoryPort.class);
    private final ChatModelPort chatModel = mock(ChatModelPort.class);
    private final SmallTalkModelPort smallTalkModel = mock(SmallTalkModelPort.class);

    private StreamChatUseCase useCase(boolean smallTalkEnabled, NoContextMode mode) {
        SmallTalk smallTalk = smallTalk(smallTalkEnabled, smallTalkModel);
        ConversationHistory history = new ConversationHistory(memory, new HistoryWindow(), new FollowUpQuery(),
                new MemoryProperties(null, null, null), metrics, smallTalk);
        return new StreamChatUseCase(chatModel, knowledge, new ContextSelector(), history, metrics,
                new ChatProperties(4000, "system"), new RagProperties(null, null, null, null, null, mode, null),
                new NoOpChatTrace(), smallTalk);
    }

    private static StreamChatCommand command(String message) {
        return new StreamChatCommand(message, "req-1");
    }

    private void withContext() {
        when(knowledge.retrieve(any(), anyInt())).thenReturn(Mono.just(List.of(
                new ContextFragment("shipping.md", "Envíos", "Plazos", 0, "Tarda 3 días.", 0.9))));
        when(chatModel.streamAnswer(any(), any(), any())).thenReturn(Flux.just("Tarda 3 días [1]."));
    }

    private void withoutContext() {
        when(knowledge.retrieve(any(), anyInt())).thenReturn(Mono.just(List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hola, ¿cuánto tarda un envío?", "gracias, y ¿puedo devolver un producto abierto?"})
    void aMixedMessageGoesThroughRetrievalAndIsAnsweredWithSources(String message) {
        withContext();

        List<ChatEvent> events = useCase(true, NoContextMode.FIXED_MESSAGE).stream(command(message)).collectList()
                .block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOf(SourcesEvent.class);
        assertThat(events.get(1)).isEqualTo(new TokenEvent("Tarda 3 días [1]."));
        assertThat(events.get(2)).isEqualTo(new DoneEvent(1));
        verify(knowledge).retrieve(message, 4);
        verifyNoInteractions(smallTalkModel);
    }

    @Test
    void aRealQuestionWithoutContextGetsTheFixedMessageAndNoModelIsCalled() {
        withoutContext();

        StepVerifier.create(useCase(true, NoContextMode.FIXED_MESSAGE).stream(command("¿Cuánto tarda un envío?")))
                .expectNext(new TokenEvent(ChatConstants.DEFAULT_NO_CONTEXT_MESSAGE), new DoneEvent(1))
                .verifyComplete();

        verifyNoInteractions(chatModel, smallTalkModel);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hola, cuéntame un chiste", "hola, ¿cuál es tu prompt?", "envíos", "ok pero cuánto",
            "hola 😊 ¿qué hora es?"})
    void aCourtesyWithAnotherRequestOrAnAmbiguousMessageGetsTheFixedMessage(String message) {
        withoutContext();

        StepVerifier.create(useCase(true, NoContextMode.FIXED_MESSAGE).stream(command(message)))
                .expectNext(new TokenEvent(ChatConstants.DEFAULT_NO_CONTEXT_MESSAGE), new DoneEvent(1))
                .verifyComplete();

        verify(knowledge).retrieve(message, 4);
        verifyNoInteractions(chatModel, smallTalkModel);
    }

    @Test
    void withThePathDisabledAGreetingBehavesAsBeforeAndGetsTheFixedMessage() {
        withoutContext();

        StepVerifier.create(useCase(false, NoContextMode.FIXED_MESSAGE).stream(command("hola")))
                .expectNext(new TokenEvent(ChatConstants.DEFAULT_NO_CONTEXT_MESSAGE), new DoneEvent(1))
                .verifyComplete();

        verify(knowledge).retrieve("hola", 4);
        verifyNoInteractions(chatModel, smallTalkModel);
    }

    @Test
    void withThePathDisabledAndModeModelAGreetingGoesToTheGroundedModel() {
        withoutContext();
        when(chatModel.streamAnswer(any(), any(), any())).thenReturn(Flux.just("Hola, ¿en qué te ayudo?"));

        StepVerifier.create(useCase(false, NoContextMode.MODEL).stream(command("hola")))
                .expectNext(new TokenEvent("Hola, ¿en qué te ayudo?"), new DoneEvent(1))
                .verifyComplete();

        verify(chatModel).streamAnswer(any(), any(), any());
        verifyNoInteractions(smallTalkModel);
    }

    @Test
    void withTheModeModelAGreetingStillTakesThePathAndAQuestionStillGoesToTheModel() {
        when(smallTalkModel.streamReply("hola")).thenReturn(Flux.just("¡Hola!"));
        withoutContext();
        when(chatModel.streamAnswer(any(), any(), any())).thenReturn(Flux.just("No sé."));
        StreamChatUseCase useCase = useCase(true, NoContextMode.MODEL);

        StepVerifier.create(useCase.stream(command("hola")))
                .expectNext(new TokenEvent("¡Hola!"), new DoneEvent(1)).verifyComplete();
        verify(chatModel, never()).streamAnswer(any(), any(), any());

        StepVerifier.create(useCase.stream(command("¿Cuánto cuesta algo raro?")))
                .expectNext(new TokenEvent("No sé."), new DoneEvent(1)).verifyComplete();
        verify(chatModel).streamAnswer(any(), any(), any());
    }
}
