package com.shoplab.helpdesk.chat.infrastructure.out.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import com.shoplab.helpdesk.chat.GatewayStubs;
import com.shoplab.helpdesk.chat.application.port.out.GenerationScope;
import com.shoplab.helpdesk.chat.application.port.out.PromptCatalogPort;
import com.shoplab.helpdesk.chat.application.port.out.TurnTrace;
import com.shoplab.helpdesk.chat.domain.exception.PromptOverrideException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.PromptMessage;
import com.shoplab.helpdesk.chat.domain.model.PromptOverrides;
import com.shoplab.helpdesk.chat.domain.model.PromptReference;
import com.shoplab.helpdesk.chat.domain.model.ResolvedPrompt;
import com.shoplab.helpdesk.chat.domain.model.RetrievalTrace;
import com.shoplab.helpdesk.chat.domain.model.ToolCallTrace;
import com.shoplab.helpdesk.chat.domain.model.constants.PromptConstants;
import com.shoplab.helpdesk.chat.domain.model.constants.TracingConstants;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.support.PostgresTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * A turn takes the version of every prompt when it starts and keeps it until it ends, so a label that moves during
 * the answer never changes it (FR-012); the next turn, of the same conversation, uses the new version and keeps its
 * history (story 4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class PromptSnapshotTests {

    private static final ContextFragment FRAGMENT = new ContextFragment("a.md", "Doc", "", 0, "Texto.", 0.9);

    @RegisterExtension
    static WireMockExtension ollama = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    @DynamicPropertySource
    static void ollamaUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai.base-url", () -> ollama.baseUrl() + "/v1");
    }

    /** A catalog whose label can be moved by the test, as someone does it in Langfuse. */
    static final class MovableCatalog implements PromptCatalogPort {

        /** The versions from this one up do not exist in Langfuse. */
        static final int UNAVAILABLE_FROM = 90;

        private final AtomicInteger version = new AtomicInteger(1);

        void moveLabelTo(int newVersion) {
            version.set(newVersion);
        }

        @Override
        public ResolvedPrompt current(PromptName name) {
            return of(name, version.get());
        }

        @Override
        public Mono<ResolvedPrompt> pinned(PromptName name, int pinnedVersion) {
            return pinnedVersion >= UNAVAILABLE_FROM ? Mono.error(PromptOverrideException.unavailable())
                    : Mono.just(of(name, pinnedVersion));
        }

        private static ResolvedPrompt of(PromptName name, int version) {
            String text = switch (name) {
                case CHAT_SYSTEM -> "Sistema v" + version;
                case SMALL_TALK -> "Cortesía v" + version + " temas: {topics}";
                case CONTEXT -> "Contexto v" + version + ": {context} || {query}";
                case EMPTY_CONTEXT -> "Sin contexto v" + version + ": {query}";
            };
            return new ResolvedPrompt(text, PromptReference.langfuse(name, version));
        }
    }

    @TestConfiguration
    static class Prompts {

        @Bean
        @Primary
        MovableCatalog movableCatalog() {
            return new MovableCatalog();
        }
    }

    /** A trace that only remembers the prompts it was told. */
    static final class PromptRecorder implements TurnTrace {

        private List<PromptReference> prompts = List.of();
        private boolean overridden;

        @Override
        public void promptsUsed(List<PromptReference> used, boolean overridden) {
            prompts = used;
            this.overridden = overridden;
        }

        @Override
        public void historyTurns(int turns) {
            // not needed here
        }

        @Override
        public void retrieval(RetrievalTrace retrieval, Duration elapsed) {
            // not needed here
        }

        @Override
        public void fixedMessageAnswer() {
            // not needed here
        }

        @Override
        public void smallTalkAnswer() {
            // not needed here
        }

        @Override
        public GenerationScope startGeneration(String model, Map<String, Object> parameters,
                List<PromptMessage> messages) {
            return GenerationScope.NONE;
        }

        @Override
        public void toolCall(ToolCallTrace call, Duration elapsed) {
            // not needed here
        }
    }

    @Autowired
    private GatewayChatAdapter adapter;

    @Autowired
    private MovableCatalog catalog;

    private final PromptRecorder trace = new PromptRecorder();

    @BeforeEach
    void stubTheModel() {
        ollama.resetAll();
        catalog.moveLabelTo(1);
        GatewayStubs.stubAnyChat(ollama, GatewayStubs.streaming(GatewayStubs.body("ok")));
    }

    private String lastBody() {
        List<LoggedRequest> requests = ollama.findAll(postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH)));
        return requests.get(requests.size() - 1).getBodyAsString();
    }

    private static List<String> contentOf(String body, String role) {
        List<String> contents = JsonPath.read(body, "$.messages[?(@.role == '" + role + "')].content");
        return contents.stream().map(content -> content.replace("\r\n", "\n")).toList();
    }

    @Test
    void aLabelMovedAfterTheTurnStartedDoesNotReachItsMessagesNorItsTrace() {
        StepVerifier.create(adapter.streamAnswer("¿Plazo?", List.of(), List.of(FRAGMENT))
                .contextWrite(context -> context.put(TracingConstants.TURN_TRACE_KEY, trace)))
                .then(() -> catalog.moveLabelTo(2))
                .expectNext("ok").verifyComplete();

        assertThat(contentOf(lastBody(), "system")).containsExactly("Sistema v1");
        assertThat(contentOf(lastBody(), "user")).singleElement().asString().startsWith("Contexto v1:");
        assertThat(trace.prompts).extracting(PromptReference::version).containsExactly(1, 1);
    }

    @Test
    void theNextTurnOfTheSameConversationUsesTheNewVersionAndKeepsTheHistory() {
        StepVerifier.create(adapter.streamAnswer("Primera", List.of(), List.of(FRAGMENT))).expectNext("ok")
                .verifyComplete();
        catalog.moveLabelTo(2);

        StepVerifier.create(adapter.streamAnswer("Segunda", List.of(new ConversationTurn("Primera", "Respuesta uno")),
                List.of(FRAGMENT)).contextWrite(context -> context.put(TracingConstants.TURN_TRACE_KEY, trace)))
                .expectNext("ok").verifyComplete();

        String body = lastBody();
        assertThat(contentOf(body, "system")).containsExactly("Sistema v2");
        assertThat(contentOf(body, "user")).hasSize(2);
        assertThat(contentOf(body, "user").get(0)).contains("Primera");
        assertThat(contentOf(body, "assistant")).containsExactly("Respuesta uno");
        assertThat(contentOf(body, "user").get(1)).startsWith("Contexto v2:");
        assertThat(trace.prompts).extracting(PromptReference::version).containsExactly(2, 2);
    }

    @Test
    void theTemplateWithAndWithoutContextComeFromTheSameSnapshotAsTheSystemPrompt() {
        StepVerifier.create(adapter.streamAnswer("¿Plazo?", List.of(), List.of())
                .contextWrite(context -> context.put(TracingConstants.TURN_TRACE_KEY, trace)))
                .then(() -> catalog.moveLabelTo(3))
                .expectNext("ok").verifyComplete();

        assertThat(contentOf(lastBody(), "system")).containsExactly("Sistema v1");
        assertThat(contentOf(lastBody(), "user")).singleElement().asString().startsWith("Sin contexto v1:");
        assertThat(trace.prompts).extracting(prompt -> prompt.name())
                .containsExactly(PromptName.CHAT_SYSTEM, PromptName.EMPTY_CONTEXT);
    }

    @Test
    void aCourtesyKeepsItsVersionWhileTheLabelMovesToo() {
        StepVerifier.create(adapter.streamReply("Hola")
                .contextWrite(context -> context.put(TracingConstants.TURN_TRACE_KEY, trace)))
                .then(() -> catalog.moveLabelTo(2))
                .expectNext("ok").verifyComplete();

        assertThat(contentOf(lastBody(), "system")).singleElement().asString().startsWith("Cortesía v1 temas: ")
                .doesNotContain("{topics}");
        assertThat(trace.prompts).extracting(PromptReference::version).containsExactly(1);
    }

    // --- versions fixed by an evaluation (FR-017, FR-023) ---

    private static PromptOverrides fixing(PromptName name, int version) {
        return new PromptOverrides(Map.of(name, version));
    }

    @Test
    void aFixedVersionIsUsedForThatPromptAndTheRestKeepTheirProductionVersion() {
        StepVerifier.create(adapter.streamAnswer("¿Plazo?", List.of(), List.of(FRAGMENT))
                .contextWrite(context -> context.put(PromptConstants.OVERRIDES_CONTEXT_KEY,
                        fixing(PromptName.CHAT_SYSTEM, 5)).put(TracingConstants.TURN_TRACE_KEY, trace)))
                .expectNext("ok").verifyComplete();

        assertThat(contentOf(lastBody(), "system")).containsExactly("Sistema v5");
        assertThat(contentOf(lastBody(), "user")).singleElement().asString().startsWith("Contexto v1:");
        assertThat(trace.prompts).extracting(PromptReference::version).containsExactly(5, 1);
        assertThat(trace.overridden).isTrue();
        assertThat(catalog.current(PromptName.CHAT_SYSTEM).reference().version())
                .as("production did not move").isEqualTo(1);
    }

    @Test
    void aFixedVersionThatIsNotAvailableFailsBeforeTheModelIsCalledAndNeverFallsBack() {
        StepVerifier.create(adapter.streamAnswer("¿Plazo?", List.of(), List.of(FRAGMENT))
                .contextWrite(context -> context.put(PromptConstants.OVERRIDES_CONTEXT_KEY,
                        fixing(PromptName.CHAT_SYSTEM, 99))))
                .expectError(PromptOverrideException.class).verify();

        assertThat(ollama.findAll(postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH)))).isEmpty();
    }

    @Test
    void twoRequestsAtTheSameTimeWithDifferentVersionsDoNotAffectEachOther() {
        Mono<List<String>> a = adapter.streamAnswer("A", List.of(), List.of(FRAGMENT))
                .contextWrite(context -> context.put(PromptConstants.OVERRIDES_CONTEXT_KEY,
                        fixing(PromptName.CHAT_SYSTEM, 7)))
                .collectList();
        Mono<List<String>> b = adapter.streamAnswer("B", List.of(), List.of(FRAGMENT))
                .contextWrite(context -> context.put(PromptConstants.OVERRIDES_CONTEXT_KEY,
                        fixing(PromptName.CHAT_SYSTEM, 8)))
                .collectList();
        Mono<List<String>> normal = adapter.streamAnswer("C", List.of(), List.of(FRAGMENT)).collectList();

        StepVerifier.create(Mono.zip(a, b, normal)).expectNextCount(1).verifyComplete();

        List<String> systems = ollama.findAll(postRequestedFor(urlEqualTo(GatewayStubs.CHAT_PATH))).stream()
                .map(request -> contentOf(request.getBodyAsString(), "system").get(0)).toList();
        assertThat(systems).containsExactlyInAnyOrder("Sistema v7", "Sistema v8", "Sistema v1");
    }
}
