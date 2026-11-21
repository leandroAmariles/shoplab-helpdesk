package com.shoplab.helpdesk.eval.domain.model;

import java.util.List;

/** What the assistant uses at this moment: its retrieval parameters and the prompt version it serves for each prompt. */
public record AssistantSettingsSnapshot(RetrievalConfig retrieval, List<PromptVersion> prompts) {
}
