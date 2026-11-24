package com.shoplab.helpdesk.eval.infrastructure.out.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.eval.domain.model.PromptVersion;
import com.shoplab.helpdesk.eval.domain.model.enums.PromptSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.cloud.contract.stubrunner.junit.StubRunnerExtension;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/** Consumer contract tests for {@code eval -> chat} using Spring Cloud Contract Stub Runner. */
class EvalChatContractConsumerTest {

    private static final String GROUP_ID = "com.shoplab";
    private static final String ARTIFACT_ID = "helpdesk-backend";

    @RegisterExtension
    static final StubRunnerExtension STUB_RUNNER = createStubRunner();

    private static StubRunnerExtension createStubRunner() {
        String mode = System.getProperty("shoplab.contracts.stubs-mode", "AUTO");
        if ("REMOTE".equalsIgnoreCase(mode)) {
            return new StubRunnerExtension()
                    .downloadStub(GROUP_ID, ARTIFACT_ID, "+", "stubs")
                    .stubsMode(StubRunnerProperties.StubsMode.REMOTE)
                    .repoRoot("https://maven.pkg.github.com/leandroAmariles/shoplab-helpdesk");
        }
        if ("LOCAL".equalsIgnoreCase(mode)) {
            return new StubRunnerExtension()
                    .downloadStub(GROUP_ID, ARTIFACT_ID, "+", "stubs")
                    .stubsMode(StubRunnerProperties.StubsMode.LOCAL);
        }
        Path generatedStubs = Path.of("target", "stubs").toAbsolutePath();
        if (Files.isDirectory(generatedStubs)) {
            return new StubRunnerExtension()
                    .downloadStub(GROUP_ID, ARTIFACT_ID)
                    .stubsMode(StubRunnerProperties.StubsMode.REMOTE)
                    .repoRoot("stubs://" + generatedStubs.toUri());
        }
        return new StubRunnerExtension()
                .downloadStub(GROUP_ID, ARTIFACT_ID, "+", "stubs")
                .stubsMode(StubRunnerProperties.StubsMode.LOCAL);
    }

    private HttpAssistantSettingsClient client() {
        int port = STUB_RUNNER.findStubUrl(GROUP_ID, ARTIFACT_ID).getPort();
        return new HttpAssistantSettingsClient(WebClient.builder().baseUrl("http://localhost:" + port).build());
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
