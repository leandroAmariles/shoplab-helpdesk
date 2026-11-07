package com.shoplab.helpdesk.chat.infrastructure.out.persistence;

import com.shoplab.helpdesk.chat.application.port.out.ConversationMemoryPort;
import com.shoplab.helpdesk.chat.domain.exception.ConversationMemoryException;
import com.shoplab.helpdesk.chat.domain.model.ConversationId;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.enums.MemoryOperation;
import com.shoplab.helpdesk.shared.infrastructure.out.blocking.BlockingCalls;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Keeps the turns in {@code chat.conversation_turn}, which only this module reads and writes. Blocking JDBC runs
 * through {@link BlockingCalls}; neither the texts nor the conversation id appear in an error or a log.
 */
@Component
@DependsOn("moduleSchemaMigrator")
public class JdbcConversationMemoryAdapter implements ConversationMemoryPort {

    private static final String RECENT = """
            SELECT question, answer FROM chat.conversation_turn
            WHERE conversation_id = ?
            ORDER BY id DESC
            LIMIT ?
            """;
    private static final String INSERT = """
            INSERT INTO chat.conversation_turn (conversation_id, question, answer) VALUES (?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public JdbcConversationMemoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Mono<List<ConversationTurn>> recent(ConversationId id, int limit) {
        return BlockingCalls.call(() -> read(id, limit))
                .onErrorMap(DataAccessException.class, error -> new ConversationMemoryException(
                        MemoryOperation.READ, error));
    }

    @Override
    public Mono<Void> saveTurn(ConversationId id, ConversationTurn turn) {
        return BlockingCalls.run(() -> jdbc.update(INSERT, UUID.fromString(id.value()), turn.question(),
                        turn.answer()))
                .onErrorMap(DataAccessException.class, error -> new ConversationMemoryException(
                        MemoryOperation.WRITE, error));
    }

    private List<ConversationTurn> read(ConversationId id, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ConversationTurn> newestFirst = new ArrayList<>(jdbc.query(RECENT,
                (row, index) -> new ConversationTurn(row.getString("question"), row.getString("answer")),
                UUID.fromString(id.value()), limit));
        Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }
}
