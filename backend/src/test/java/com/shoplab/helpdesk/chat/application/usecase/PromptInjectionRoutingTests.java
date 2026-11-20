package com.shoplab.helpdesk.chat.application.usecase;

import static com.shoplab.helpdesk.chat.support.SmallTalkTestSupport.smallTalk;
import static com.shoplab.helpdesk.chat.support.SmallTalkTestSupport.classifier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
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
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.chat.domain.model.enums.NoContextMode;
import com.shoplab.helpdesk.chat.infrastructure.out.tracing.NoOpChatTrace;
import com.shoplab.helpdesk.chat.support.InjectionMessages;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

/**
 * The routing side of prompt injection (LAB-114, SC-004): a message with an instruction is never a courtesy, so it
 * does not take the small-talk path, and without context it gets the fixed message without reaching any model.
 */
class PromptInjectionRoutingTests {

    private final ChatMetricsPort metrics = mock(ChatMetricsPort.class);
    private final KnowledgeContextPort knowledge = mock(KnowledgeContextPort.class);
    private final ChatModelPort chatModel = mock(ChatModelPort.class);
    private final SmallTalkModelPort smallTalkModel = mock(SmallTalkModelPort.class);

    private StreamChatUseCase useCase;

    @BeforeEach
    void build() {
        when(knowledge.retrieve(any(), anyInt())).thenReturn(Mono.just(List.of()));
        SmallTalk smallTalk = smallTalk(true, smallTalkModel);
        ConversationHistory history = new ConversationHistory(mock(ConversationMemoryPort.class),
                new HistoryWindow(), new FollowUpQuery(), new MemoryProperties(null, null, null), metrics, smallTalk);
        useCase = new StreamChatUseCase(chatModel, knowledge, new ContextSelector(), history, metrics,
                new ChatProperties(4000),
                new RagProperties(null, null, null, NoContextMode.FIXED_MESSAGE, null),
                new NoOpChatTrace(), smallTalk);
    }

    @ParameterizedTest
    @MethodSource("injections")
    void anInjectionIsNeverSmallTalkAndWithoutContextGetsTheFixedMessage(String injection) {
        List<ChatEvent> events = useCase.stream(new StreamChatCommand(injection, "req-1")).collectList().block();

        assertThat(classifier().isSmallTalk(injection)).isFalse();
        assertThat(events).first().isEqualTo(new TokenEvent(ChatConstants.DEFAULT_NO_CONTEXT_MESSAGE));
        assertThat(events).hasSize(2);
        verifyNoInteractions(chatModel, smallTalkModel);
    }

    static List<String> injections() {
        return InjectionMessages.INJECTIONS;
    }

    @ParameterizedTest
    @ValueSource(strings = {"hola", "gracias", "¿qué puedes hacer?"})
    void aRealCourtesyStillDoesNotCarryAnyRequest(String courtesy) {
        assertThat(classifier().isSmallTalk(courtesy)).isTrue();
    }
}
