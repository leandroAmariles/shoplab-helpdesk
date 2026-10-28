package com.shoplab.helpdesk.retrieval.infrastructure.out.persistence;

import com.shoplab.helpdesk.retrieval.application.port.out.KnowledgeBasePort;
import com.shoplab.helpdesk.retrieval.domain.model.IngestedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.ParsedChunk;
import com.shoplab.helpdesk.retrieval.domain.model.ParsedDocument;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.out.blocking.BlockingCalls;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * Stores documents and chunks in the {@code retrieval} schema, which only this module reads and writes. The chunks
 * are stored without vector: the embeddings story completes them. Blocking JDBC runs through {@link BlockingCalls}.
 */
@Component
@DependsOn("moduleSchemaMigrator")
public class JdbcKnowledgeBaseAdapter implements KnowledgeBasePort {

    private static final int BATCH_SIZE = 500;

    private static final String UPSERT_DOCUMENT = """
            INSERT INTO retrieval.document (source, title, metadata)
            VALUES (?, ?, jsonb_build_object('format', ?::text))
            ON CONFLICT (source) DO UPDATE
            SET title = EXCLUDED.title, metadata = EXCLUDED.metadata, updated_at = now()
            RETURNING id
            """;
    private static final String DELETE_CHUNKS = "DELETE FROM retrieval.chunk WHERE document_id = ?";
    private static final String INSERT_CHUNK = """
            INSERT INTO retrieval.chunk (document_id, chunk_index, content, metadata)
            VALUES (?, ?, ?, jsonb_build_object('document', ?::text, 'section', ?::text, 'position', ?::int))
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcKnowledgeBaseAdapter(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public Mono<IngestedDocument> replaceDocument(ParsedDocument parsed) {
        return BlockingCalls.call(() -> transaction.execute(status -> store(parsed)))
                .onErrorMap(error -> error instanceof DataAccessException || error instanceof TransactionException,
                        error -> ExceptionUtil.technical(ResponseCodeEnum.DATABASE_UNAVAILABLE, error));
    }

    private IngestedDocument store(ParsedDocument parsed) {
        UUID documentId = jdbc.queryForObject(UPSERT_DOCUMENT, UUID.class, parsed.name(), parsed.title(),
                parsed.format().getExternalValue());
        jdbc.update(DELETE_CHUNKS, documentId);
        jdbc.batchUpdate(INSERT_CHUNK, parsed.chunks(), BATCH_SIZE, (statement, chunk) -> bind(statement, documentId,
                parsed.name(), chunk));
        return new IngestedDocument(documentId, parsed.name(), parsed.title(), parsed.chunks().size());
    }

    private static void bind(java.sql.PreparedStatement statement, UUID documentId, String document, ParsedChunk chunk)
            throws java.sql.SQLException {
        statement.setObject(1, documentId);
        statement.setInt(2, chunk.position());
        statement.setString(3, chunk.text());
        statement.setString(4, document);
        statement.setString(5, chunk.section());
        statement.setInt(6, chunk.position());
    }
}
