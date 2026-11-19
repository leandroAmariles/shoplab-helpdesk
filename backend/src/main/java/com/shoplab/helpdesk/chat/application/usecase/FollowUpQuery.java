package com.shoplab.helpdesk.chat.application.usecase;

import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The text the knowledge base is searched with. A follow-up such as "¿y si es internacional?" has no words of its own
 * topic, so the previous question of the conversation goes before it: the search finds the documents of that topic
 * without a second call to the model. The message handed to the model is not changed.
 */
@Component
public class FollowUpQuery {

    private static final String SEPARATOR = "\n";

    /**
     * @param message        the customer's current message; always kept whole
     * @param history        the turns handed to the model, oldest first; only their questions are used
     * @param retrievalTurns how many of the last questions go before the message; 0 searches with the message alone
     * @param maxCharacters  most characters of the query; the oldest questions are left out first
     */
    public String compose(String message, List<ConversationTurn> history, int retrievalTurns, int maxCharacters) {
        if (retrievalTurns <= 0 || history.isEmpty()) {
            return message;
        }
        List<String> questions = history.subList(Math.max(0, history.size() - retrievalTurns), history.size())
                .stream().map(ConversationTurn::question).toList();
        int first = 0;
        while (first < questions.size() && length(questions, first, message) > maxCharacters) {
            first++;
        }
        StringBuilder query = new StringBuilder();
        for (int index = first; index < questions.size(); index++) {
            query.append(questions.get(index)).append(SEPARATOR);
        }
        return query.append(message).toString();
    }

    private static int length(List<String> questions, int from, String message) {
        int total = message.length();
        for (int index = from; index < questions.size(); index++) {
            total += questions.get(index).length() + SEPARATOR.length();
        }
        return total;
    }
}
