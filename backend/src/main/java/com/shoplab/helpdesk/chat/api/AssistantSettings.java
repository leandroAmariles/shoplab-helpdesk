package com.shoplab.helpdesk.chat.api;

import java.util.List;

/**
 * The settings of the assistant at one moment.
 *
 * @param topK                 fragments asked of the search for each question
 * @param similarityThreshold  lowest similarity a fragment may have
 * @param maxContextCharacters most characters of context handed to the model
 * @param prompts              the version served of each prompt, in a stable order
 */
public record AssistantSettings(int topK, double similarityThreshold, int maxContextCharacters,
        List<ServedPrompt> prompts) {
}
