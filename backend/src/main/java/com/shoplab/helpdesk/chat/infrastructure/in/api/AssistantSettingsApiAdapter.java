package com.shoplab.helpdesk.chat.infrastructure.in.api;

import com.shoplab.helpdesk.chat.api.AssistantSettings;
import com.shoplab.helpdesk.chat.api.AssistantSettingsApi;
import com.shoplab.helpdesk.chat.api.ServedPrompt;
import com.shoplab.helpdesk.chat.application.port.out.PromptCatalogPort;
import com.shoplab.helpdesk.chat.config.RagProperties;
import com.shoplab.helpdesk.chat.domain.model.PromptReference;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Serves the published settings from what the chat holds in memory: the retrieval parameters it was started with and
 * the version of each prompt its next turn would use. It changes nothing in the chat and never waits for Langfuse.
 */
@Component
public class AssistantSettingsApiAdapter implements AssistantSettingsApi {

    private final PromptCatalogPort prompts;
    private final RagProperties rag;

    public AssistantSettingsApiAdapter(PromptCatalogPort prompts, RagProperties rag) {
        this.prompts = prompts;
        this.rag = rag;
    }

    @Override
    public AssistantSettings current() {
        List<ServedPrompt> served = Arrays.stream(PromptName.values())
                .map(name -> prompts.current(name).reference())
                .map(AssistantSettingsApiAdapter::served)
                .toList();
        return new AssistantSettings(rag.topK(), rag.similarityThreshold(), rag.maxContextCharacters(), served);
    }

    private static ServedPrompt served(PromptReference reference) {
        return new ServedPrompt(reference.name().langfuseName(), reference.version(), reference.source().name());
    }
}
