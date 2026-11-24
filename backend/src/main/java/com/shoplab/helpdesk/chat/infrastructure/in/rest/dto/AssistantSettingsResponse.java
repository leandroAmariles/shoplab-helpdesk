package com.shoplab.helpdesk.chat.infrastructure.in.rest.dto;

import java.util.List;

/** HTTP response payload for {@code GET /api/assistant/settings}. */
public record AssistantSettingsResponse(
        int topK,
        double similarityThreshold,
        int maxContextCharacters,
        List<ServedPromptItem> prompts) {

    /** Served prompt entry; {@code version} is {@code null} when using packaged defaults. */
    public record ServedPromptItem(String name, Integer version, String source) {
    }
}
