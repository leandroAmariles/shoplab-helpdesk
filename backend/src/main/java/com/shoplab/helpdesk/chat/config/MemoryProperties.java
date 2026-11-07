package com.shoplab.helpdesk.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the conversation memory under {@code shoplab.chat.memory}.
 *
 * @param maxTurns            most recent turns handed to the model (from 0 to 50, default 5); 0 turns the memory off
 *                            even when the request carries a conversation id
 * @param maxHistoryCharacters most characters of history (questions and answers) handed to the model (at least 200,
 *                            default 4000); older turns are left out whole
 * @param retrievalTurns      previous questions added to the search of context (from 0 to 50, default 1); 0 searches
 *                            with the current message only
 */
@ConfigurationProperties("shoplab.chat.memory")
public record MemoryProperties(Integer maxTurns, Integer maxHistoryCharacters, Integer retrievalTurns) {

    public static final int MAX_TURNS_LIMIT = 50;

    private static final int DEFAULT_MAX_TURNS = 5;
    private static final int DEFAULT_MAX_HISTORY_CHARACTERS = 4000;
    private static final int DEFAULT_RETRIEVAL_TURNS = 1;
    private static final int MIN_HISTORY_CHARACTERS = 200;

    public MemoryProperties {
        maxTurns = maxTurns == null ? DEFAULT_MAX_TURNS : maxTurns;
        maxHistoryCharacters = maxHistoryCharacters == null ? DEFAULT_MAX_HISTORY_CHARACTERS : maxHistoryCharacters;
        retrievalTurns = retrievalTurns == null ? DEFAULT_RETRIEVAL_TURNS : retrievalTurns;
        if (maxTurns < 0 || maxTurns > MAX_TURNS_LIMIT) {
            throw new IllegalArgumentException("shoplab.chat.memory.max-turns must be between 0 and "
                    + MAX_TURNS_LIMIT + " but was " + maxTurns);
        }
        if (maxHistoryCharacters < MIN_HISTORY_CHARACTERS) {
            throw new IllegalArgumentException("shoplab.chat.memory.max-history-characters must be at least "
                    + MIN_HISTORY_CHARACTERS + " but was " + maxHistoryCharacters);
        }
        if (retrievalTurns < 0 || retrievalTurns > MAX_TURNS_LIMIT) {
            throw new IllegalArgumentException("shoplab.chat.memory.retrieval-turns must be between 0 and "
                    + MAX_TURNS_LIMIT + " but was " + retrievalTurns);
        }
    }

    /** Whether the memory is on: with no turns to keep there is nothing to read or write. */
    public boolean enabled() {
        return maxTurns > 0;
    }

    /** Previous questions the search really uses: never more than the turns kept, so max-turns 0 is enough. */
    public int effectiveRetrievalTurns() {
        return Math.min(retrievalTurns, maxTurns);
    }
}
