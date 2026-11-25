package com.shoplab.helpdesk.chat.infrastructure.out.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.config.RetrievalClientConfig;
import com.shoplab.helpdesk.chat.config.RetrievalClientProperties;
import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.cloud.contract.stubrunner.junit.StubRunnerExtension;
import org.springframework.cloud.contract.stubrunner.spring.StubRunnerProperties;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/** Consumer contract tests for {@code chat -> retrieval-service} with the stubs the service publishes. */
class ChatRetrievalContractConsumerTest {

    private static final String GROUP_ID = "com.shoplab";
    private static final String ARTIFACT_ID = "shoplab-retrieval-service";

    @RegisterExtension
    static final StubRunnerExtension STUB_RUNNER = new StubRunnerExtension()
            // The stubs jar is a test dependency of the POM, which is where its version is pinned.
            .downloadStub(GROUP_ID, ARTIFACT_ID)
            .stubsMode(StubRunnerProperties.StubsMode.CLASSPATH);

    private HttpKnowledgeSearchClient client() {
        int port = STUB_RUNNER.findStubUrl(GROUP_ID, ARTIFACT_ID).getPort();
        var properties = new RetrievalClientProperties("http://localhost:" + port, null);
        return new HttpKnowledgeSearchClient(
                new RetrievalClientConfig().retrievalSearchApi(WebClient.builder(), properties), properties.timeout());
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
