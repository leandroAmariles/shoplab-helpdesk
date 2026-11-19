package com.shoplab.helpdesk.chat.application.port.out;

import com.shoplab.helpdesk.chat.domain.model.ConversationId;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import java.util.List;
import reactor.core.publisher.Mono;

/** What the chat remembers of each conversation. Any failure is a {@code ConversationMemoryException}. */
public interface ConversationMemoryPort {

    /** The most recent {@code limit} turns of the conversation, oldest first; empty when it has none yet. */
    Mono<List<ConversationTurn>> recent(ConversationId id, int limit);

    /** Stores a complete turn in one step: a question is never stored without its answer. */
    Mono<Void> saveTurn(ConversationId id, ConversationTurn turn);
}
