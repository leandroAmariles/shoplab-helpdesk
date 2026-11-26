package com.shoplab.helpdesk.eval.infrastructure.out.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.eval.config.ChatSettingsClientConfig;
import com.shoplab.helpdesk.eval.config.ChatSettingsClientProperties;
import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.cloud.contract.stubrunner.junit.StubRunnerExtension;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/** Consumer contract tests for {@code eval -> chat-service} with the stubs the service publishes (LAB-59). */
class EvalChatContractConsumerTest {

    private static final String GROUP_ID = "com.shoplab";
    private static final String ARTIFACT_ID = "shoplab-chat-service";

    @RegisterExtension
    static final StubRunnerExtension STUB_RUNNER = new StubRunnerExtension()
            // The stubs jar is a test dependency of the POM, which is where its version is pinned.
            .downloadStub(GROUP_ID, ARTIFACT_ID)
            .stubsMode(StubRunnerProperties.StubsMode.CLASSPATH);

    private HttpAssistantSettingsClient client() {
        int port = STUB_RUNNER.findStubUrl(GROUP_ID, ARTIFACT_ID).getPort();
        var properties = new ChatSettingsClientProperties("http://localhost:" + port, null);
        return new HttpAssistantSettingsClient(
                new ChatSettingsClientConfig().chatSettingsApi(WebClient.builder(), properties), properties.timeout());
    }

    @Test
    void readsAssistantSettingsIncludingLangfuseAndNullableDefaultPromptsFromChatStub() {
        StepVerifier.create(client().current())
                .assertNext(snapshot -> {
                    assertThat(snapshot.retrieval().topK()).isEqualTo(5);
                    assertThat(snapshot.retrieval().similarityThreshold()).isEqualTo(0.45);
                    assertThat(snapshot.retrieval().maxContextCharacters()).isEqualTo(6000);
                    assertThat(snapshot.prompts()).containsExactly(
                            new PromptVersion("chat-system", 3, PromptSource.LANGFUSE, false),
                            new PromptVersion("chat-small-talk", null, PromptSource.DEFAULT, false));
                })
                .verifyComplete();
    }
}
