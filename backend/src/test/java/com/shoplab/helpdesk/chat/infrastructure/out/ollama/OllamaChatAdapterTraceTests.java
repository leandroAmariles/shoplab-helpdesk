package com.shoplab.helpdesk.chat.infrastructure.out.ollama;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.OllamaStubs;
import com.shoplab.helpdesk.chat.application.port.out.GenerationScope;
import com.shoplab.helpdesk.chat.application.port.out.TurnTrace;
import com.shoplab.helpdesk.chat.domain.exception.ChatModelException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.PromptMessage;
import com.shoplab.helpdesk.chat.domain.model.RetrievalTrace;
import com.shoplab.helpdesk.chat.domain.model.TokenUsage;
import com.shoplab.helpdesk.chat.domain.model.ToolCallTrace;
import com.shoplab.helpdesk.chat.domain.model.constants.TracingConstants;
import com.shoplab.helpdesk.support.PostgresTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** The model adapter reports the generation to the trace of the turn it finds in the Reactor context (LAB-64). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class OllamaChatAdapterTraceTests {

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ollamaUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", ollama::baseUrl);
        registry.add("shoplab.chat.system-prompt", () -> "You are the test assistant");
    }

    @Autowired
    private OllamaChatAdapter adapter;

    private final RecordingTrace trace = new RecordingTrace();

    private Flux<String> answer(List<ConversationTurn> history, List<ContextFragment> context) {
        return adapter.streamAnswer("¿Plazo?", history, context)
                .contextWrite(reactorContext -> reactorContext.put(TracingConstants.TURN_TRACE_KEY, trace));
    }

    @Test
    void theGenerationIsReportedWithTheModelTheParametersAndThePromptThatWasSent() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.bodyWithUsage(11, 7, "Diez ", "días")));
        List<ContextFragment> context = List.of(new ContextFragment("returns.md", "Devoluciones", "Plazos", 0,
                "Treinta días.", 0.9));

        StepVerifier.create(answer(List.of(new ConversationTurn("Hola", "Buenas")), context))
                .expectNext("Diez ", "días").verifyComplete();

        assertThat(trace.started).isEqualTo(1);
        assertThat(trace.model).isEqualTo("llama3.2:3b");
        assertThat(trace.parameters).containsEntry("provider", "ollama");
        assertThat(trace.messages).extracting(PromptMessage::role)
                .containsExactly("system", "user", "assistant", "user");
        assertThat(trace.messages.get(0).content()).isEqualTo("You are the test assistant");
        assertThat(trace.messages.get(3).content()).contains("Treinta días.").contains("¿Plazo?");
        assertThat(trace.firstTokens).isEqualTo(1);
        assertThat(trace.completedUsage).isEqualTo(new TokenUsage(11, 7));
        assertThat(trace.completedAnswer).isEqualTo("Diez días");
    }

    @Test
    void whenTheModelDoesNotReportTokensTheUsageIsUnknownNotZero() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("Hola")));

        StepVerifier.create(answer(List.of(), List.of())).expectNext("Hola").verifyComplete();

        assertThat(trace.completedUsage.isKnown()).isFalse();
        assertThat(trace.completedUsage.inputTokens()).isNull();
    }

    @Test
    void aFailureOfTheModelIsReportedAndStillReachesTheCaller() {
        ollama.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/api/chat"))
                .willReturn(aResponse().withStatus(500)));

        StepVerifier.create(answer(List.of(), List.of())).expectError(ChatModelException.class).verify();

        assertThat(trace.failure).isInstanceOf(ChatModelException.class);
        assertThat(trace.completedUsage).isNull();
    }

    @Test
    void aCancelledAnswerReportsWhatHadArrived() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("Diez ", "días")));

        StepVerifier.create(answer(List.of(), List.of()).take(1)).expectNext("Diez ").verifyComplete();

        assertThat(trace.cancelledAnswer).isEqualTo("Diez ");
        assertThat(trace.completedUsage).isNull();
    }

    @Test
    void withoutATraceInTheContextTheAnswerIsTheSame() {
        OllamaStubs.stubAnyChat(ollama, OllamaStubs.streaming(OllamaStubs.body("Hola")));

        StepVerifier.create(adapter.streamAnswer("¿Plazo?", List.of(), List.of())).expectNext("Hola")
                .verifyComplete();

        assertThat(trace.started).isZero();
    }

    /** Keeps what the adapter reports. */
    private static final class RecordingTrace implements TurnTrace {

        private int started;
        private int firstTokens;
        private String model;
        private Map<String, Object> parameters;
        private List<PromptMessage> messages = new ArrayList<>();
        private TokenUsage completedUsage;
        private String completedAnswer;
        private Throwable failure;
        private String cancelledAnswer;

        @Override
        public void historyTurns(int turns) {
            // not reported by the model adapter
        }

        @Override
        public void retrieval(RetrievalTrace retrieval, Duration elapsed) {
            // not reported by the model adapter
        }

        @Override
        public void fixedMessageAnswer() {
            // not reported by the model adapter
        }

        @Override
        public GenerationScope startGeneration(String model, Map<String, Object> parameters,
                List<PromptMessage> messages) {
            started++;
            this.model = model;
            this.parameters = parameters;
            this.messages = messages;
            return new GenerationScope() {
                @Override
                public void firstToken() {
                    firstTokens++;
                }

                @Override
                public void complete(TokenUsage usage, String answer) {
                    completedUsage = usage;
                    completedAnswer = answer;
                }

                @Override
                public void fail(Throwable error) {
                    failure = error;
                }

                @Override
                public void cancel(String partialAnswer) {
                    cancelledAnswer = partialAnswer;
                }
            };
        }

        @Override
        public void toolCall(ToolCallTrace call, Duration elapsed) {
            // covered by TracedToolCallbackTests
        }
    }
}
