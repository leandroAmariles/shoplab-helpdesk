package com.shoplab.helpdesk.chat.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptSource;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptValueTypesTests {

    @Test
    void aLangfuseReferenceNeedsAVersionFromOne() {
        assertThat(PromptReference.langfuse(PromptName.CHAT_SYSTEM, 3).asTraceValue()).isEqualTo("3");
        assertThatThrownBy(() -> PromptReference.langfuse(PromptName.CHAT_SYSTEM, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromptReference(PromptName.CHAT_SYSTEM, null, PromptSource.LANGFUSE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDefaultReferenceHasNoVersionAndSaysDefaultInTheTrace() {
        PromptReference reference = new PromptReference(PromptName.CONTEXT, 7, PromptSource.DEFAULT);

        assertThat(reference.version()).isNull();
        assertThat(reference.isDefault()).isTrue();
        assertThat(reference.asTraceValue()).isEqualTo("default");
    }

    @Test
    void aReferenceNeedsNameAndSource() {
        assertThatThrownBy(() -> new PromptReference(null, 1, PromptSource.LANGFUSE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PromptReference(PromptName.CHAT_SYSTEM, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aResolvedPromptNeedsTextAndReference() {
        PromptReference reference = PromptReference.defaultValue(PromptName.CHAT_SYSTEM);

        assertThat(new ResolvedPrompt("texto", reference).text()).isEqualTo("texto");
        assertThatThrownBy(() -> new ResolvedPrompt(" ", reference)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResolvedPrompt("texto", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overridesAreImmutableAndRejectBadVersions() {
        PromptOverrides overrides = new PromptOverrides(Map.of(PromptName.CHAT_SYSTEM, 3));

        assertThat(overrides.isEmpty()).isFalse();
        assertThat(overrides.versionOf(PromptName.CHAT_SYSTEM)).contains(3);
        assertThat(overrides.versionOf(PromptName.CONTEXT)).isEmpty();
        assertThat(PromptOverrides.NONE.isEmpty()).isTrue();
        assertThat(new PromptOverrides(null).isEmpty()).isTrue();
        assertThatThrownBy(() -> new PromptOverrides(Map.of(PromptName.CHAT_SYSTEM, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theNamesKnowTheirLangfuseNameResourceAndTraceKey() {
        assertThat(PromptName.fromLangfuseName("chat-small-talk")).contains(PromptName.SMALL_TALK);
        assertThat(PromptName.fromLangfuseName("otro")).isEmpty();
        assertThat(PromptName.CHAT_SYSTEM.resourcePath()).isEqualTo("prompts/chat-system.txt");
        assertThat(PromptName.EMPTY_CONTEXT.traceMetadataKey()).isEqualTo("prompt_chat_empty_context");
        assertThat(PromptName.CONTEXT.requiredPlaceholders()).containsExactly("{query}", "{context}");
    }
}
