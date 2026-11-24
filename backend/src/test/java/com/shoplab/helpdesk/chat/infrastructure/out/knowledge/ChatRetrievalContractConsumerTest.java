package com.shoplab.helpdesk.chat.infrastructure.out.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.cloud.contract.stubrunner.junit.StubRunnerExtension;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/** Consumer contract tests for {@code chat -> retrieval} using Spring Cloud Contract Stub Runner. */
class ChatRetrievalContractConsumerTest {

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

    private HttpKnowledgeSearchClient client() {
        int port = STUB_RUNNER.findStubUrl(GROUP_ID, ARTIFACT_ID).getPort();
        return new HttpKnowledgeSearchClient(WebClient.builder().baseUrl("http://localhost:" + port).build());
    }

    @Test
    void returnsContextFragmentsFromRetrievalStub() {
        StepVerifier.create(client().retrieve("politicas de devolucion", 5))
                .assertNext(fragments -> {
                    assertThat(fragments).hasSize(1);
                    ContextFragment fragment = fragments.getFirst();
                    assertThat(fragment.source()).isEqualTo("returns-policy.md");
                    assertThat(fragment.title()).isEqualTo("Política de devoluciones");
                    assertThat(fragment.section()).isEqualTo("Plazos");
                    assertThat(fragment.position()).isZero();
                    assertThat(fragment.score()).isEqualTo(0.91);
                    assertThat(fragment.text()).contains("30 días naturales");
                })
                .verifyComplete();
    }

    @Test
    void returnsEmptyListWhenRetrievalStubReturnsNoMatches() {
        StepVerifier.create(client().retrieve("consulta sin coincidencias", 5))
                .assertNext(fragments -> assertThat(fragments).isEmpty())
                .verifyComplete();
    }

    @Test
    void maps422ValidationErrorFromRetrievalStubToKnowledgeRetrievalException() {
        StepVerifier.create(client().retrieve("   ", 5))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(KnowledgeRetrievalException.class);
                    KnowledgeRetrievalException ex = (KnowledgeRetrievalException) error;
                    assertThat(ex.getResponseCode()).isEqualTo(ResponseCodeEnum.CHAT_CONTEXT_UNAVAILABLE);
                })
                .verify();
    }
}
