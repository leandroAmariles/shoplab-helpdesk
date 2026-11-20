package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import com.shoplab.helpdesk.chat.application.usecase.PromptValidator;
import com.shoplab.helpdesk.chat.domain.model.PromptReference;
import com.shoplab.helpdesk.chat.domain.model.ResolvedPrompt;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptRejection;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;

/**
 * The default text of each prompt: the single copy of it in the repository, in {@code prompts/*.txt}. It is what the
 * chat uses while Langfuse has no valid version to give, and what is published to a Langfuse that has none. It is the
 * last safety net, so it is validated like any other version and the application does not start if one is wrong.
 */
public final class PromptDefaults {

    private final Map<PromptName, ResolvedPrompt> prompts = new EnumMap<>(PromptName.class);

    /**
     * @throws IllegalStateException when a default text is missing, unreadable or does not pass the validation
     */
    public PromptDefaults(PromptValidator validator) {
        for (PromptName name : PromptName.values()) {
            String text = read(name);
            Optional<PromptRejection> rejection = validator.validate(name, PromptValidator.TEXT_TYPE, text);
            if (rejection.isPresent()) {
                throw new IllegalStateException("The default text of the prompt " + name.langfuseName() + " ("
                        + name.resourcePath() + ") is not valid: " + rejection.get().reason());
            }
            prompts.put(name, new ResolvedPrompt(text, PromptReference.defaultValue(name)));
        }
    }

    public ResolvedPrompt get(PromptName name) {
        return prompts.get(name);
    }

    private static String read(PromptName name) {
        ClassPathResource resource = new ClassPathResource(name.resourcePath());
        try (InputStream stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The default text of the prompt " + name.langfuseName() + " ("
                    + name.resourcePath() + ") cannot be read", e);
        }
    }
}
