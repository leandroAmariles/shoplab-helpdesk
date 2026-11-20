package com.shoplab.helpdesk.chat.domain.model;

import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Versions of prompts that one request fixes, instead of the ones labelled {@code production}. Only the evaluation
 * uses it (LAB-85); a customer's request never carries any.
 *
 * @param versions version (at least 1) for each fixed prompt; empty means every prompt keeps its {@code production}
 */
public record PromptOverrides(Map<PromptName, Integer> versions) {

    public static final PromptOverrides NONE = new PromptOverrides(Map.of());

    public PromptOverrides {
        Map<PromptName, Integer> copy = new EnumMap<>(PromptName.class);
        if (versions != null) {
            versions.forEach((name, version) -> {
                if (name == null || version == null || version < 1) {
                    throw new IllegalArgumentException("A fixed version is a number from 1 for a known prompt");
                }
                copy.put(name, version);
            });
        }
        versions = Map.copyOf(copy);
    }

    public boolean isEmpty() {
        return versions.isEmpty();
    }

    public Optional<Integer> versionOf(PromptName name) {
        return Optional.ofNullable(versions.get(name));
    }
}
