package com.shoplab.helpdesk.chat.infrastructure.out.ollama;

import com.shoplab.helpdesk.chat.application.port.out.TurnTrace;
import java.util.Arrays;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Collects the agent tools that other modules publish as {@link ToolCallbackProvider} beans. {@code chat} composes
 * them without knowing which module they come from; each module owns its own tools (specs/LAB-105, research D-5).
 */
@Component
public class ToolCallbackComposer {

    private final ObjectProvider<ToolCallbackProvider> providers;

    public ToolCallbackComposer(ObjectProvider<ToolCallbackProvider> providers) {
        this.providers = providers;
    }

    public List<ToolCallback> compose() {
        return compose(TurnTrace.NONE);
    }

    /** The same tools, each one reporting its calls to the trace of the turn that is being answered (LAB-64). */
    public List<ToolCallback> compose(TurnTrace trace) {
        return providers.orderedStream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .map(callback -> trace == TurnTrace.NONE ? callback : new TracedToolCallback(callback, trace))
                .toList();
    }
}
