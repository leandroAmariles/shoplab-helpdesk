package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.application.port.out.GenerationScope;
import com.shoplab.helpdesk.chat.application.port.out.TurnTrace;
import com.shoplab.helpdesk.chat.config.TracingProperties;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.PromptMessage;
import com.shoplab.helpdesk.chat.domain.model.PromptReference;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.TokenUsage;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** The trace says which prompt, and which version of it, produced each answer (LAB-85, contracts/trace-prompts.md). */
class LangfuseChatTracePromptsTests {

    private static final List<PromptMessage> PROMPT = List.of(new PromptMessage("system", "Reglas"),
            new PromptMessage("user", "Pregunta"));

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final DerivedTraceIdGenerator ids = new DerivedTraceIdGenerator();
    private final SdkTracerProvider provider = SdkTracerProvider.builder().setIdGenerator(ids)
            .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    private final LangfuseChatTrace trace = new LangfuseChatTrace(provider, ids,
            new TracingProperties(true, null, "pk", "sk", "dev", null, null, null, null, null, null, null));

    @AfterEach
    void close() {
        provider.close();
    }

    private void run(Function<TurnTrace, Flux<ChatEvent>> turn) {
        StepVerifier.create(trace.traceTurn(new StreamChatCommand("Pregunta", "req-prompts", null), turn))
                .thenConsumeWhile(event -> true).verifyComplete();
    }

    /** A turn that tells the prompts and then generates, like the model adapter does. */
    private Flux<ChatEvent> turn(TurnTrace turn, List<PromptReference> prompts, boolean overridden) {
        turn.promptsUsed(prompts, overridden);
        GenerationScope generation = turn.startGeneration("llama", Map.of("provider", "ollama"), PROMPT);
        generation.firstToken();
        generation.complete(new TokenUsage(1, 1), "Respuesta");
        return Flux.just(new TokenEvent("Respuesta"), new DoneEvent(0));
    }

    private SpanData span(String name) {
        return exporter.getFinishedSpanItems().stream().filter(item -> item.getName().equals(name)).findFirst()
                .orElseThrow();
    }

    private static String traceMeta(SpanData span, String key) {
        return span.getAttributes().get(LangfuseAttributes.traceMetadata(key));
    }

    private static String generationMeta(SpanData span, String key) {
        return span.getAttributes().get(LangfuseAttributes.observationMetadata(key));
    }

    private static final AttributeKey<String> NATIVE_NAME = AttributeKey.stringKey("langfuse.observation.prompt.name");
    private static final AttributeKey<Long> NATIVE_VERSION =
            AttributeKey.longKey("langfuse.observation.prompt.version");

    @Test
    void aGroundedTurnLinksTheSystemPromptAndRecordsBothAsMetadata() {
        run(turn -> turn(turn, List.of(PromptReference.langfuse(PromptName.CHAT_SYSTEM, 3),
                PromptReference.langfuse(PromptName.CONTEXT, 2)), false));

        SpanData generation = span("generation");
        SpanData root = span("chat-turn");
        assertThat(generation.getAttributes().get(NATIVE_NAME)).isEqualTo("chat-system");
        assertThat(generation.getAttributes().get(NATIVE_VERSION)).isEqualTo(3L);
        assertThat(generationMeta(generation, "prompt.chat-system")).isEqualTo("3");
        assertThat(generationMeta(generation, "prompt.chat-context")).isEqualTo("2");
        assertThat(traceMeta(root, "prompt_chat_system")).isEqualTo("3");
        assertThat(traceMeta(root, "prompt_chat_context")).isEqualTo("2");
        assertThat(traceMeta(root, "prompt_override")).isNull();
    }

    @Test
    void aCourtesyLinksTheSmallTalkPromptAndRecordsNothingElse() {
        run(turn -> turn(turn, List.of(PromptReference.langfuse(PromptName.SMALL_TALK, 4)), false));

        SpanData generation = span("generation");
        SpanData root = span("chat-turn");
        assertThat(generation.getAttributes().get(NATIVE_NAME)).isEqualTo("chat-small-talk");
        assertThat(generation.getAttributes().get(NATIVE_VERSION)).isEqualTo(4L);
        assertThat(traceMeta(root, "prompt_chat_small_talk")).isEqualTo("4");
        assertThat(traceMeta(root, "prompt_chat_context")).isNull();
        assertThat(traceMeta(root, "prompt_chat_empty_context")).isNull();
        assertThat(traceMeta(root, "prompt_chat_system")).isNull();
    }

    @Test
    void theDefaultTextIsMarkedAsDefaultAndIsNotLinkedBecauseThereIsNoVersion() {
        run(turn -> turn(turn, List.of(PromptReference.defaultValue(PromptName.CHAT_SYSTEM),
                PromptReference.langfuse(PromptName.EMPTY_CONTEXT, 5)), false));

        SpanData generation = span("generation");
        SpanData root = span("chat-turn");
        assertThat(generation.getAttributes().get(NATIVE_NAME)).isNull();
        assertThat(generation.getAttributes().get(NATIVE_VERSION)).isNull();
        assertThat(generationMeta(generation, "prompt.chat-system")).isEqualTo("default");
        assertThat(traceMeta(root, "prompt_chat_system")).isEqualTo("default");
        assertThat(traceMeta(root, "prompt_chat_empty_context")).isEqualTo("5");
    }

    @Test
    void aRequestThatFixedVersionsIsMarkedAsOverriddenOnTheTrace() {
        run(turn -> turn(turn, List.of(PromptReference.langfuse(PromptName.CHAT_SYSTEM, 9)), true));

        assertThat(traceMeta(span("chat-turn"), "prompt_override")).isEqualTo("true");
        assertThat(traceMeta(span("chat-turn"), "prompt_chat_system")).isEqualTo("9");
    }

    @Test
    void aTurnThatNeverToldItsPromptsLeavesNoPromptAttributes() {
        run(turn -> {
            GenerationScope generation = turn.startGeneration("llama", Map.of("provider", "ollama"), PROMPT);
            generation.complete(new TokenUsage(1, 1), "Respuesta");
            return Flux.just(new DoneEvent(0));
        });

        assertThat(span("generation").getAttributes().get(NATIVE_NAME)).isNull();
        assertThat(traceMeta(span("chat-turn"), "prompt_chat_system")).isNull();
    }

    @Test
    void theTextOfThePromptNeverGoesInTheAttributesOfThePromptReference() {
        run(turn -> turn(turn, List.of(PromptReference.langfuse(PromptName.CHAT_SYSTEM, 3)), false));

        exporter.getFinishedSpanItems().forEach(span -> span.getAttributes().forEach((key, value) -> {
            if (key.getKey().contains("prompt")) {
                assertThat(String.valueOf(value)).doesNotContain("Reglas");
            }
        }));
    }
}
