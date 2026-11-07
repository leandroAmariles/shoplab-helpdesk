package com.shoplab.helpdesk.chat.application.usecase;

import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.application.port.out.ConversationMemoryPort;
import com.shoplab.helpdesk.chat.config.MemoryProperties;
import com.shoplab.helpdesk.chat.domain.exception.ConversationMemoryException;
import com.shoplab.helpdesk.chat.domain.model.ConversationId;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.HistorySelection;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.chat.domain.model.enums.MemoryOperation;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * What the chat does with the memory of a conversation: tell whether a request has one, read the part of it that goes
 * to the model, build the text the knowledge base is searched with, and store a finished turn. A failure of the memory
 * is counted and logged here with the request id and the operation, never with the texts or the conversation id.
 */
@Component
public class ConversationHistory {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationHistory.class);

    private final ConversationMemoryPort memory;
    private final HistoryWindow window;
    private final FollowUpQuery followUp;
    private final MemoryProperties properties;
    private final ChatMetricsPort metrics;

    public ConversationHistory(ConversationMemoryPort memory, HistoryWindow window, FollowUpQuery followUp,
            MemoryProperties properties, ChatMetricsPort metrics) {
        this.memory = memory;
        this.window = window;
        this.followUp = followUp;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * The conversation of a request, empty when it has none or the memory is turned off. A malformed identifier is an
     * invalid request even when the memory is off, so a client finds its mistake in every environment.
     */
    public Optional<ConversationId> conversationOf(StreamChatCommand command) {
        return ConversationId.optional(command.conversationId()).filter(id -> properties.enabled());
    }

    /** The turns handed to the model; a conversation that has none yet is empty, a memory that fails is an error. */
    public Mono<HistorySelection> load(ConversationId id, String requestId) {
        return memory.recent(id, properties.maxTurns() + 1)
                .map(turns -> window.select(turns, properties.maxTurns(), properties.maxHistoryCharacters()))
                .doOnError(ConversationMemoryException.class, error -> failed(requestId, MemoryOperation.READ))
                .doOnNext(selection -> record(requestId, selection));
    }

    /** The text for the search of context: the previous question of the conversation, if any, then the message. */
    public String searchText(String message, HistorySelection selection) {
        return followUp.compose(message, selection.turns(), properties.effectiveRetrievalTurns(),
                ChatConstants.FOLLOW_UP_QUERY_MAX_CHARACTERS);
    }

    /** Stores the finished turn; it completes only once the turn is stored. */
    public Mono<Void> save(ConversationId id, String requestId, String question, String answer) {
        return memory.saveTurn(id, new ConversationTurn(question, answer))
                .doOnError(ConversationMemoryException.class, error -> failed(requestId, MemoryOperation.WRITE));
    }

    private void failed(String requestId, MemoryOperation operation) {
        metrics.recordMemoryFailure(operation);
        LOG.warn("The conversation memory failed: requestId={}, operation={}", requestId, operation.tagValue());
    }

    private void record(String requestId, HistorySelection selection) {
        int characters = selection.turns().stream()
                .mapToInt(turn -> turn.question().length() + turn.answer().length()).sum();
        metrics.recordHistory(selection.turns().size() * 2, selection.trimmed());
        LOG.info("Conversation history: requestId={}, turns={}, characters={}, trimmed={}", requestId,
                selection.turns().size(), characters, selection.trimmed());
    }
}
