package com.shoplab.helpdesk.chat.application.usecase;

import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.HistorySelection;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Decides which turns of a conversation reach the model: the most recent ones, as many as fit in the character budget.
 * Turns go in whole or not at all, because cutting one in half changes what it says. The citation marks of past
 * answers are removed, because their numbers pointed at fragments of that answer and the model would cite them again.
 */
@Component
public class HistoryWindow {

    private static final Pattern CITATION_MARK = Pattern.compile("\\s*\\[\\d+(?:\\s*[,-]\\s*\\d+)*]");

    /**
     * @param turns         the turns read from the memory, oldest first (one more than {@code maxTurns} tells that
     *                      some were left out)
     * @param maxTurns      most turns kept; 0 or less keeps none
     * @param maxCharacters most characters of questions and answers kept in total
     */
    public HistorySelection select(List<ConversationTurn> turns, int maxTurns, int maxCharacters) {
        if (maxTurns <= 0 || turns.isEmpty()) {
            return HistorySelection.empty();
        }
        boolean trimmed = turns.size() > maxTurns;
        List<ConversationTurn> kept = new ArrayList<>(
                turns.subList(Math.max(0, turns.size() - maxTurns), turns.size()).stream()
                        .map(HistoryWindow::withoutCitationMarks)
                        .toList());
        while (!kept.isEmpty() && characters(kept) > maxCharacters) {
            kept.remove(0);
            trimmed = true;
        }
        return new HistorySelection(kept, trimmed);
    }

    private static ConversationTurn withoutCitationMarks(ConversationTurn turn) {
        String clean = CITATION_MARK.matcher(turn.answer()).replaceAll("").strip();
        return clean.isEmpty() ? turn : new ConversationTurn(turn.question(), clean);
    }

    private static int characters(List<ConversationTurn> turns) {
        return turns.stream().mapToInt(turn -> turn.question().length() + turn.answer().length()).sum();
    }
}
