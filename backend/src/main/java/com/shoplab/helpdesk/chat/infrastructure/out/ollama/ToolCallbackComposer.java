package com.shoplab.helpdesk.chat.infrastructure.out.ollama;

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
        return providers.orderedStream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .toList();
    }
}
