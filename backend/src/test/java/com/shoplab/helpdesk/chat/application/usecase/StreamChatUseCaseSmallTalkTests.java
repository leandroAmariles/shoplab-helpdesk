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
import com.shoplab.helpdesk.chat.domain.exception.ChatModelException;
import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.ErrorEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.SourcesEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.enums.NoContextMode;
import com.shoplab.helpdesk.chat.infrastructure.out.tracing.NoOpChatTrace;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

/** The small-talk path of the use case (LAB-114): a courtesy is answered without history, search or sources. */
class StreamChatUseCaseSmallTalkTests {

    private static final String CONVERSATION = UUID.randomUUID().toString();

    private final ChatMetricsPort metrics = mock(ChatMetricsPort.class);
    private final KnowledgeContextPort knowledge = mock(KnowledgeContextPort.class);
    private final ConversationMemoryPort memory = mock(ConversationMemoryPort.class);
    private final ChatModelPort chatModel = mock(ChatModelPort.class);

    private StreamChatUseCase useCase(SmallTalkModelPort replyModel) {
        SmallTalk smallTalk = smallTalk(true, replyModel);
        ConversationHistory history = new ConversationHistory(memory, new HistoryWindow(), new FollowUpQuery(),
                new MemoryProperties(null, null, null), metrics, smallTalk);
        return new StreamChatUseCase(chatModel, knowledge, new ContextSelector(), history, metrics,
                new ChatProperties(4000, "system"),
                new RagProperties(null, null, null, null, null, NoContextMode.FIXED_MESSAGE, null),
                new NoOpChatTrace(), smallTalk);
    }

    private static StreamChatCommand command(String message) {
        return new StreamChatCommand(message, "req-1");
    }

    @Test
    void aGreetingIsAnsweredWithTokensAndDoneAndNoSources() {
        StreamChatUseCase useCase = useCase(message -> Flux.just("¡Ho", "la!", " Pregunta."));

        StepVerifier.create(useCase.stream(command("hola")))
                .expectNext(new TokenEvent("¡Ho"), new TokenEvent("la!"), new TokenEvent(" Pregunta."))
                .expectNext(new DoneEvent(3))
                .verifyComplete();
    }

    @Test
    void theModelReceivesTheMessageAsTheCustomerWroteIt() {
        SmallTalkModelPort model = mock(SmallTalkModelPort.class);
        when(model.streamReply("¡HOLAAA!!")).thenReturn(Flux.just("Hola"));

        StepVerifier.create(useCase(model).stream(command("¡HOLAAA!!"))).expectNextCount(2).verifyComplete();

        verify(model).streamReply("¡HOLAAA!!");
    }

    @Test
    void nothingIsSearchedAndTheGroundedModelIsNotCalled() {
        StreamChatUseCase useCase = useCase(message -> Flux.just("Hola"));

        StepVerifier.create(useCase.stream(command("gracias"))).expectNextCount(2).verifyComplete();

        verifyNoInteractions(knowledge, chatModel);
    }

    @Test
    void theHistoryIsNotReadEvenWithAConversation() {
        when(memory.saveTurn(any(), any())).thenReturn(Mono.empty());
        StreamChatUseCase useCase = useCase(message -> Flux.just("Hola"));

        StepVerifier.create(useCase.stream(new StreamChatCommand("hola", "req-1", CONVERSATION)))
                .expectNextCount(2).verifyComplete();

        verify(memory, never()).recent(any(), anyInt());
        verifyNoInteractions(knowledge);
    }

    @Test
    void aCourtesyAnswersEvenIfTheSearchIsDown() {
        when(knowledge.retrieve(any(), anyInt())).thenReturn(Mono.error(new KnowledgeRetrievalException(
                new IllegalStateException("embeddings down"))));
        StreamChatUseCase useCase = useCase(message -> Flux.just("Hola"));

        StepVerifier.create(useCase.stream(command("hola")))
                .expectNext(new TokenEvent("Hola"), new DoneEvent(1))
                .verifyComplete();
    }

    @Test
    void theTurnIsStoredBeforeDoneIsSent() {
        AtomicBoolean stored = new AtomicBoolean();
        when(memory.saveTurn(any(), any())).thenReturn(Mono.fromRunnable(() -> stored.set(true)));
        StreamChatUseCase useCase = useCase(message -> Flux.just("¡Ho", "la!"));

        StepVerifier.create(useCase.stream(new StreamChatCommand("hola", "req-1", CONVERSATION)))
                .expectNextCount(2)
                .assertNext(event -> {
                    assertThat(event).isEqualTo(new DoneEvent(2));
                    assertThat(stored).isTrue();
                })
                .verifyComplete();

        verify(memory).saveTurn(any(), org.mockito.ArgumentMatchers.eq(new ConversationTurn("hola", "¡Hola!")));
    }

    @Test
    void aFailureBeforeTheFirstTokenIsTheHttpErrorOfTheModel() {
        StreamChatUseCase useCase = useCase(message -> Flux.error(new ChatModelException(new RuntimeException("down"))));

        StepVerifier.create(useCase.stream(command("hola")))
                .expectError(ChatModelException.class)
                .verify();
    }

    @Test
    void aFailureAfterSomeTokensIsAFinalErrorEventAndTheTurnIsNotStored() {
        StreamChatUseCase useCase = useCase(message -> Flux.concat(Flux.just("¡Ho"),
                Flux.error(new ChatModelException(new RuntimeException("cut")))));

        StepVerifier.create(useCase.stream(new StreamChatCommand("hola", "req-1", CONVERSATION)))
                .expectNext(new TokenEvent("¡Ho"))
                .expectNext(new ErrorEvent(ResponseCodeEnum.CHAT_STREAM_FAILED.getCode(),
                        ResponseCodeEnum.CHAT_STREAM_FAILED.getDefaultMessage()))
                .verifyComplete();

        verify(memory, never()).saveTurn(any(), any());
    }

    @Test
    void cancellingTheSubscriptionCancelsTheModel() {
        TestPublisher<String> reply = TestPublisher.create();
        StreamChatUseCase useCase = useCase(message -> reply.flux());

        StepVerifier.create(useCase.stream(command("hola")))
                .then(() -> reply.next("Ho"))
                .expectNext(new TokenEvent("Ho"))
                .thenCancel()
                .verify();

        reply.assertCancelled();
    }

    @Test
    void anInvalidMessageIsRejectedBeforeBeingClassified() {
        SmallTalkModelPort model = mock(SmallTalkModelPort.class);

        StepVerifier.create(useCase(model).stream(command("   ")))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.INVALID_CHAT_MESSAGE))
                .verify();

        verifyNoInteractions(model);
    }

    @Test
    void theEventsOfACourtesyNeverIncludeSources() {
        StreamChatUseCase useCase = useCase(message -> Flux.just("a", "b", "c"));

        List<ChatEvent> events = useCase.stream(command("buenas tardes")).collectList().block();

        assertThat(events).noneMatch(SourcesEvent.class::isInstance);
        assertThat(events).hasSize(4);
    }
}
