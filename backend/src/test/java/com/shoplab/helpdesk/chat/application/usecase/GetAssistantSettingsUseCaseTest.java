package com.shoplab.helpdesk.chat.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.api.AssistantSettings;
import com.shoplab.helpdesk.chat.api.AssistantSettingsApi;
import com.shoplab.helpdesk.chat.api.ServedPrompt;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GetAssistantSettingsUseCaseTest {

    @Mock
    private AssistantSettingsApi settingsApi;

    @Test
    void returnsCurrentSettingsReactivelyIncludingDefaultNullVersions() {
        AssistantSettings expected = new AssistantSettings(4, 0.6, 8000, List.of(
                new ServedPrompt("chat-system", 3, "LANGFUSE"),
                new ServedPrompt("chat-small-talk", null, "DEFAULT")));
        when(settingsApi.current()).thenReturn(expected);
        GetAssistantSettingsUseCase useCase = new GetAssistantSettingsUseCase(settingsApi);

        StepVerifier.create(useCase.current())
                .assertNext(actual -> {
                    assertThat(actual.topK()).isEqualTo(4);
                    assertThat(actual.similarityThreshold()).isEqualTo(0.6);
                    assertThat(actual.maxContextCharacters()).isEqualTo(8000);
                    assertThat(actual.prompts()).containsExactly(
                            new ServedPrompt("chat-system", 3, "LANGFUSE"),
                            new ServedPrompt("chat-small-talk", null, "DEFAULT"));
                })
                .verifyComplete();

        verify(settingsApi).current();
    }
}
