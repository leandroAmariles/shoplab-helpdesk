package com.shoplab.helpdesk.eval.infrastructure.out.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import com.shoplab.helpdesk.chat.api.AssistantSettings;
import com.shoplab.helpdesk.chat.api.ServedPrompt;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ChatAssistantSettingsAdapterTests {

    @Test
    void whatTheChatPublishesBecomesTheSnapshotOfTheModuleWithNoFixedPrompts() {
        ChatAssistantSettingsAdapter adapter = new ChatAssistantSettingsAdapter(() -> new AssistantSettings(6, 0.5,
                1200, List.of(new ServedPrompt("chat-system", 3, "LANGFUSE"),
                        new ServedPrompt("chat-empty-context", null, "DEFAULT"))));

        StepVerifier.create(adapter.current()).assertNext(snapshot -> {
            assertThat(snapshot.retrieval().topK()).isEqualTo(6);
            assertThat(snapshot.retrieval().similarityThreshold()).isEqualTo(0.5);
            assertThat(snapshot.retrieval().maxContextCharacters()).isEqualTo(1200);
            assertThat(snapshot.retrieval().depth()).isNull();
            assertThat(snapshot.prompts()).containsExactly(
                    new PromptVersion("chat-system", 3, PromptSource.LANGFUSE, false),
                    new PromptVersion("chat-empty-context", null, PromptSource.DEFAULT, false));
        }).verifyComplete();
    }

    @Test
    void aSourceThatIsNotKnownIsAWiringMistakeNotAValue() {
        ChatAssistantSettingsAdapter adapter = new ChatAssistantSettingsAdapter(() -> new AssistantSettings(4, 0.6,
                8000, List.of(new ServedPrompt("chat-system", 1, "ELSEWHERE"))));

        StepVerifier.create(adapter.current()).expectError(IllegalStateException.class).verify();
    }
}
