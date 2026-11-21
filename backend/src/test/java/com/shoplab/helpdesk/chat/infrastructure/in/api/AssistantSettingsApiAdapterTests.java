package com.shoplab.helpdesk.chat.infrastructure.in.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.api.AssistantSettings;
import com.shoplab.helpdesk.chat.api.ServedPrompt;
import com.shoplab.helpdesk.chat.application.port.out.PromptCatalogPort;
import com.shoplab.helpdesk.chat.config.RagProperties;
import com.shoplab.helpdesk.chat.domain.model.PromptReference;
import com.shoplab.helpdesk.chat.domain.model.ResolvedPrompt;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AssistantSettingsApiAdapterTests {

    private static PromptCatalogPort catalog(Map<PromptName, PromptReference> references) {
        return new PromptCatalogPort() {
            @Override
            public ResolvedPrompt current(PromptName name) {
                return new ResolvedPrompt("texto de " + name.langfuseName(), references.get(name));
            }

            @Override
            public Mono<ResolvedPrompt> pinned(PromptName name, int version) {
                throw new UnsupportedOperationException("the settings never ask for a fixed version");
            }
        };
    }

    private static Map<PromptName, PromptReference> langfuseVersions() {
        return Map.of(
                PromptName.CHAT_SYSTEM, PromptReference.langfuse(PromptName.CHAT_SYSTEM, 3),
                PromptName.SMALL_TALK, PromptReference.langfuse(PromptName.SMALL_TALK, 1),
                PromptName.CONTEXT, PromptReference.langfuse(PromptName.CONTEXT, 2),
                PromptName.EMPTY_CONTEXT, PromptReference.defaultValue(PromptName.EMPTY_CONTEXT));
    }

    @Test
    void everyPromptIsReportedInAStableOrderWithItsVersionAndItsSource() {
        AssistantSettingsApiAdapter adapter = new AssistantSettingsApiAdapter(catalog(langfuseVersions()),
                new RagProperties(null, null, null, null, null));

        AssistantSettings settings = adapter.current();

        assertThat(settings.prompts()).containsExactly(
                new ServedPrompt("chat-system", 3, "LANGFUSE"),
                new ServedPrompt("chat-small-talk", 1, "LANGFUSE"),
                new ServedPrompt("chat-context", 2, "LANGFUSE"),
                new ServedPrompt("chat-empty-context", null, "DEFAULT"));
    }

    @Test
    void theRetrievalParametersAreThoseTheChatWasStartedWith() {
        AssistantSettingsApiAdapter adapter = new AssistantSettingsApiAdapter(catalog(langfuseVersions()),
                new RagProperties(6, 0.5, 1200, null, null));

        AssistantSettings settings = adapter.current();

        assertThat(settings.topK()).isEqualTo(6);
        assertThat(settings.similarityThreshold()).isEqualTo(0.5);
        assertThat(settings.maxContextCharacters()).isEqualTo(1200);
    }

    @Test
    void itFollowsTheCatalogEveryTimeItIsAsked() {
        Map<PromptName, PromptReference> references = new java.util.EnumMap<>(langfuseVersions());
        AssistantSettingsApiAdapter adapter = new AssistantSettingsApiAdapter(catalog(references),
                new RagProperties(null, null, null, null, null));
        references.put(PromptName.CHAT_SYSTEM, PromptReference.langfuse(PromptName.CHAT_SYSTEM, 4));

        assertThat(adapter.current().prompts().get(0)).isEqualTo(new ServedPrompt("chat-system", 4, "LANGFUSE"));
    }
}
