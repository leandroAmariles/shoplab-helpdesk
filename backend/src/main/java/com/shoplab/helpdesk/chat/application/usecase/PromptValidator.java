package com.shoplab.helpdesk.chat.application.usecase;

import com.shoplab.helpdesk.chat.domain.exception.PromptRenderException;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptRejection;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether a version of a prompt can be used (FR-004). A prompt edited in Langfuse is untrusted until it passes:
 * a bad one is dropped and the chat keeps the last valid text. The rules are applied in order and the first one that
 * fails is the reason; the reason never contains the text of the prompt.
 */
public final class PromptValidator {

    /** The type Langfuse gives to a prompt that is a single text (the other one is a list of chat messages). */
    public static final String TEXT_TYPE = "text";

    private static final Map<String, String> SAMPLE_VALUES = Map.of("query", "question", "context", "fragments");

    private final int maxCharacters;

    public PromptValidator(int maxCharacters) {
        this.maxCharacters = maxCharacters;
    }

    /**
     * @param name the prompt the text is meant for
     * @param type the type Langfuse reports for the version
     * @param text the text of the version
     * @return the reason it cannot be used, or empty when it can
     */
    public Optional<PromptRejection> validate(PromptName name, String type, String text) {
        if (!TEXT_TYPE.equals(type)) {
            return Optional.of(PromptRejection.NOT_TEXT);
        }
        if (text == null || text.isBlank()) {
            return Optional.of(PromptRejection.BLANK);
        }
        if (text.length() > maxCharacters) {
            return Optional.of(PromptRejection.TOO_LONG);
        }
        for (String placeholder : name.requiredPlaceholders()) {
            if (!text.contains(placeholder)) {
                return Optional.of(PromptRejection.MISSING_PLACEHOLDER);
            }
        }
        if (name == PromptName.CONTEXT && !isRenderable(text)) {
            return Optional.of(PromptRejection.NOT_RENDERABLE);
        }
        return Optional.empty();
    }

    /** The context template is rendered for every grounded answer: it can only use the variables the turn fills in. */
    private static boolean isRenderable(String text) {
        try {
            PromptRenderer.render(text, SAMPLE_VALUES);
            return true;
        } catch (PromptRenderException e) {
            return false;
        }
    }
}
