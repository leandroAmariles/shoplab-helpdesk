package com.shoplab.helpdesk.chat.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptRejection;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PromptValidatorTests {

    private static final int MAX = 200;
    private final PromptValidator validator = new PromptValidator(MAX);

    @Test
    void aSystemPromptNeedsNoMarkers() {
        assertThat(validator.validate(PromptName.CHAT_SYSTEM, "text", "Reglas con {llaves} literales.")).isEmpty();
    }

    @Test
    void theSmallTalkPromptNeedsTheTopicsMarker() {
        assertThat(validator.validate(PromptName.SMALL_TALK, "text", "Temas: {topics}")).isEmpty();
        assertThat(validator.validate(PromptName.SMALL_TALK, "text", "Saluda."))
                .contains(PromptRejection.MISSING_PLACEHOLDER);
    }

    @Test
    void theContextTemplateNeedsQueryAndContext() {
        assertThat(validator.validate(PromptName.CONTEXT, "text", "{context}\n{query}")).isEmpty();
        assertThat(validator.validate(PromptName.CONTEXT, "text", "{context}"))
                .contains(PromptRejection.MISSING_PLACEHOLDER);
        assertThat(validator.validate(PromptName.CONTEXT, "text", "{query}"))
                .contains(PromptRejection.MISSING_PLACEHOLDER);
    }

    @Test
    void theContextTemplateWithAnUnknownVariableCannotBeRendered() {
        assertThat(validator.validate(PromptName.CONTEXT, "text", "{context} {query} {cliente}"))
                .contains(PromptRejection.NOT_RENDERABLE);
    }

    @Test
    void theEmptyContextTemplateNeedsTheQuery() {
        assertThat(validator.validate(PromptName.EMPTY_CONTEXT, "text", "Pregunta: {query}")).isEmpty();
        assertThat(validator.validate(PromptName.EMPTY_CONTEXT, "text", "Sin marcador"))
                .contains(PromptRejection.MISSING_PLACEHOLDER);
    }

    @Test
    void aChatTypePromptIsNotText() {
        assertThat(validator.validate(PromptName.CHAT_SYSTEM, "chat", "Reglas")).contains(PromptRejection.NOT_TEXT);
        assertThat(validator.validate(PromptName.CHAT_SYSTEM, null, "Reglas")).contains(PromptRejection.NOT_TEXT);
    }

    @Test
    void aBlankPromptIsRejected() {
        assertThat(validator.validate(PromptName.CHAT_SYSTEM, "text", "")).contains(PromptRejection.BLANK);
        assertThat(validator.validate(PromptName.CHAT_SYSTEM, "text", " \n\t ")).contains(PromptRejection.BLANK);
        assertThat(validator.validate(PromptName.CHAT_SYSTEM, "text", null)).contains(PromptRejection.BLANK);
    }

    @Test
    void aPromptOverTheLimitIsRejectedAndTheLimitItselfIsAccepted() {
        assertThat(validator.validate(PromptName.CHAT_SYSTEM, "text", "a".repeat(MAX))).isEmpty();
        assertThat(validator.validate(PromptName.CHAT_SYSTEM, "text", "a".repeat(MAX + 1)))
                .contains(PromptRejection.TOO_LONG);
    }

    @Test
    void theRulesAreCheckedInOrder() {
        // not text wins over blank; blank wins over the missing marker
        assertThat(validator.validate(PromptName.SMALL_TALK, "chat", " ")).contains(PromptRejection.NOT_TEXT);
        assertThat(validator.validate(PromptName.SMALL_TALK, "text", " ")).contains(PromptRejection.BLANK);
    }

    @Test
    void theReasonsAreShortWordsThatNeverCarryTheText() {
        Optional<PromptRejection> rejection = validator.validate(PromptName.SMALL_TALK, "text", "secreto-del-prompt");

        assertThat(rejection).map(PromptRejection::reason).contains("missing-placeholder");
        assertThat(PromptRejection.values()).extracting(PromptRejection::reason)
                .containsExactly("not-text", "blank", "too-long", "missing-placeholder", "not-renderable");
    }
}
