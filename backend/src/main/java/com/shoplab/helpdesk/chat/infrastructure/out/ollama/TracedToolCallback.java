package com.shoplab.helpdesk.chat.infrastructure.out.ollama;

import com.shoplab.helpdesk.chat.application.port.out.TurnTrace;
import com.shoplab.helpdesk.chat.domain.model.ToolCallTrace;
import java.time.Duration;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Hands every call of a tool to the model unchanged and reports it to the trace of the turn: name, arguments, result,
 * duration and whether it failed (specs/LAB-64, FR-012). A failure is rethrown as it came; the trace only keeps the
 * kind of error, never its text, which could carry data of the conversation.
 */
class TracedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final TurnTrace trace;

    TracedToolCallback(ToolCallback delegate, TurnTrace trace) {
        this.delegate = delegate;
        this.trace = trace;
    }

    @Override
    public @NonNull ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public @NonNull ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public @NonNull String call(@NonNull String toolInput) {
        return traced(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public @NonNull String call(@NonNull String toolInput, ToolContext toolContext) {
        return traced(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String traced(String toolInput, Supplier<String> call) {
        String name = delegate.getToolDefinition().name();
        long startedAt = System.nanoTime();
        try {
            String result = call.get();
            report(new ToolCallTrace(name, toolInput, result, false, null), startedAt);
            return result;
        } catch (RuntimeException error) {
            report(new ToolCallTrace(name, toolInput, null, true, error.getClass().getSimpleName()), startedAt);
            throw error;
        }
    }

    private void report(ToolCallTrace call, long startedAt) {
        try {
            trace.toolCall(call, Duration.ofNanos(System.nanoTime() - startedAt));
        } catch (RuntimeException ignored) {
            // the recording must never change what the model gets
        }
    }
}
