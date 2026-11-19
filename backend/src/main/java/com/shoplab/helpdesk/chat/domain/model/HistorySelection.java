package com.shoplab.helpdesk.chat.domain.model;

import java.util.List;

/**
 * The part of a conversation handed to the model.
 *
 * @param turns   the turns kept, oldest first
 * @param trimmed whether some turn was left out because of the limit of turns or of characters
 */
public record HistorySelection(List<ConversationTurn> turns, boolean trimmed) {

    public HistorySelection {
        turns = List.copyOf(turns);
    }

    public static HistorySelection empty() {
        return new HistorySelection(List.of(), false);
    }
}
