package com.shoplab.helpdesk.retrieval.infrastructure.out.persistence;

import com.shoplab.helpdesk.retrieval.application.port.out.EmbeddingStorePort;
import com.shoplab.helpdesk.retrieval.domain.model.ChunkEmbedding;
import com.shoplab.helpdesk.retrieval.domain.model.ChunkToEmbed;
import com.shoplab.helpdesk.retrieval.domain.model.PendingDocument;
import com.shoplab.helpdesk.retrieval.domain.model.SearchResult;
import com.shoplab.helpdesk.retrieval.domain.model.constants.RetrievalConstants;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexScope;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.out.blocking.BlockingCalls;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reads the chunks that wait for a vector, stores the vectors and the state of the document, and runs the similarity
 * search in the {@code retrieval} schema. Vectors are stored per document in one transaction that first checks the
 * document still has the version that was read. Blocking JDBC runs through {@link BlockingCalls}.
 */
@Component
@DependsOn("moduleSchemaMigrator")
public class JdbcEmbeddingStoreAdapter implements EmbeddingStorePort {

    private static final int BATCH_SIZE = 500;

    private static final String SELECT_DOCUMENTS = """
            SELECT d.id, d.source, d.updated_at FROM retrieval.document d
            WHERE EXISTS (SELECT 1 FROM retrieval.chunk c WHERE c.document_id = d.id)
            """;
    private static final String PENDING_FILTER = "AND d.index_status = 'PENDING'";
    private static final String RETRY_FILTER = "AND d.index_status IN ('PENDING', 'ERROR')";
    private static final String OTHER_MODEL_FILTER = """
            AND (d.index_status IN ('PENDING', 'ERROR') OR EXISTS (
                SELECT 1 FROM retrieval.chunk c
                WHERE c.document_id = d.id AND (c.embedding IS NULL OR c.embedding_model <> ?)))
            """;
    private static final String ORDER_DOCUMENTS = " ORDER BY d.source";
    private static final String SELECT_CHUNKS = """
            SELECT id, chunk_index, content FROM retrieval.chunk WHERE document_id = ? ORDER BY chunk_index
            """;
    private static final String LOCK_DOCUMENT = "SELECT updated_at FROM retrieval.document WHERE id = ? FOR UPDATE";
    private static final String UPDATE_CHUNK_VECTOR = """
            UPDATE retrieval.chunk SET embedding = ?::public.vector, embedding_model = ? WHERE id = ?
            """;
    private static final String MARK_INDEXED = """
            UPDATE retrieval.document SET index_status = 'INDEXED', index_error = NULL, indexed_at = now()
            WHERE id = ?
            """;
    private static final String MARK_FAILED = """
            UPDATE retrieval.document SET index_status = 'ERROR', index_error = ?, indexed_at = NULL
            WHERE id = ? AND updated_at = ?
            """;
    private static final String SEARCH = """
            SELECT c.id, c.document_id, d.source, COALESCE(c.metadata ->> 'section', '') AS section, c.chunk_index,
                   c.content, 1 - (c.embedding <=> ?::public.vector) AS score
            FROM retrieval.chunk c
            JOIN retrieval.document d ON d.id = c.document_id
            WHERE c.embedding IS NOT NULL AND c.embedding_model = ?
            ORDER BY c.embedding <=> ?::public.vector
            LIMIT ?
            """;

    private static final String OTHER_MODELS_EXIST = """
            SELECT EXISTS (SELECT 1 FROM retrieval.chunk WHERE embedding IS NOT NULL AND embedding_model <> ?)
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcEmbeddingStoreAdapter(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public Flux<PendingDocument> findDocuments(IndexScope scope, String model) {
        return BlockingCalls.call(() -> headers(scope, model))
                .flatMapMany(Flux::fromIterable)
                .concatMap(header -> BlockingCalls.call(() -> withChunks(header)))
                .onErrorMap(JdbcEmbeddingStoreAdapter::isDatabaseFailure, JdbcEmbeddingStoreAdapter::unavailable);
    }

    @Override
    public Mono<Boolean> saveEmbeddings(PendingDocument document, List<ChunkEmbedding> vectors, String model) {
        return BlockingCalls.call(() -> {
            requireExpectedSize(vectors);
            return transaction.execute(status -> {
                boolean saved = store(document, vectors, model);
                if (!saved) {
                    status.setRollbackOnly();
                }
                return saved;
            });
        }).onErrorMap(JdbcEmbeddingStoreAdapter::isDatabaseFailure, JdbcEmbeddingStoreAdapter::unavailable);
    }

    @Override
    public Mono<Void> markFailed(PendingDocument document, String reason) {
        return BlockingCalls.run(() -> jdbc.update(MARK_FAILED, reason, document.documentId(),
                        OffsetDateTime.ofInstant(document.version(), ZoneOffset.UTC)))
                .onErrorMap(JdbcEmbeddingStoreAdapter::isDatabaseFailure, JdbcEmbeddingStoreAdapter::unavailable);
    }

    @Override
    public Mono<List<SearchResult>> search(float[] query, String model, int limit) {
        return BlockingCalls.call(() -> {
            String literal = VectorLiteral.of(query);
            return jdbc.query(SEARCH, (rs, row) -> new SearchResult(rs.getObject("id", UUID.class),
                    rs.getObject("document_id", UUID.class), rs.getString("source"), rs.getString("section"),
                    rs.getInt("chunk_index"), rs.getString("content"), rs.getDouble("score")),
                    literal, model, literal, limit);
        }).onErrorMap(JdbcEmbeddingStoreAdapter::isDatabaseFailure, JdbcEmbeddingStoreAdapter::unavailable);
    }

    @Override
    public Mono<Boolean> hasChunksFromOtherModels(String model) {
        return BlockingCalls.call(() -> jdbc.queryForObject(OTHER_MODELS_EXIST, Boolean.class, model))
                .onErrorMap(JdbcEmbeddingStoreAdapter::isDatabaseFailure, JdbcEmbeddingStoreAdapter::unavailable);
    }

    private List<DocumentHeader> headers(IndexScope scope, String model) {
        return switch (scope) {
            case PENDING -> jdbc.query(SELECT_DOCUMENTS + PENDING_FILTER + ORDER_DOCUMENTS, DocumentHeader::of);
            case RETRY -> jdbc.query(SELECT_DOCUMENTS + RETRY_FILTER + ORDER_DOCUMENTS, DocumentHeader::of);
            case OTHER_MODEL -> jdbc.query(SELECT_DOCUMENTS + OTHER_MODEL_FILTER + ORDER_DOCUMENTS,
                    DocumentHeader::of, model);
            case ALL -> jdbc.query(SELECT_DOCUMENTS + ORDER_DOCUMENTS, DocumentHeader::of);
        };
    }

    private PendingDocument withChunks(DocumentHeader header) {
        List<ChunkToEmbed> chunks = jdbc.query(SELECT_CHUNKS, (rs, row) -> new ChunkToEmbed(
                rs.getObject("id", UUID.class), rs.getInt("chunk_index"), rs.getString("content")), header.id());
        return new PendingDocument(header.id(), header.source(), header.version(), chunks);
    }

    private boolean store(PendingDocument document, List<ChunkEmbedding> vectors, String model) {
        List<OffsetDateTime> current = jdbc.query(LOCK_DOCUMENT,
                (rs, row) -> rs.getObject("updated_at", OffsetDateTime.class), document.documentId());
        if (current.isEmpty() || !current.get(0).toInstant().equals(document.version())) {
            return false;
        }
        int[][] updated = jdbc.batchUpdate(UPDATE_CHUNK_VECTOR, vectors, BATCH_SIZE, (statement, vector) -> {
            statement.setString(1, VectorLiteral.of(vector.vector()));
            statement.setString(2, model);
            statement.setObject(3, vector.chunkId());
        });
        for (int[] counts : updated) {
            for (int count : counts) {
                if (count == 0) {
                    return false;
                }
            }
        }
        jdbc.update(MARK_INDEXED, document.documentId());
        return true;
    }

    private static void requireExpectedSize(List<ChunkEmbedding> vectors) {
        for (ChunkEmbedding vector : vectors) {
            if (vector.vector() == null || vector.vector().length != RetrievalConstants.EMBEDDING_DIMENSIONS) {
                throw ExceptionUtil.technical(ResponseCodeEnum.EMBEDDING_DIMENSION_MISMATCH,
                        new IllegalArgumentException("A vector does not have "
                                + RetrievalConstants.EMBEDDING_DIMENSIONS + " values"));
            }
        }
    }

    private static boolean isDatabaseFailure(Throwable error) {
        return error instanceof DataAccessException || error instanceof TransactionException;
    }

    private static Throwable unavailable(Throwable error) {
        return ExceptionUtil.technical(ResponseCodeEnum.DATABASE_UNAVAILABLE, error);
    }

    private record DocumentHeader(UUID id, String source, Instant version) {

        static DocumentHeader of(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
            return new DocumentHeader(rs.getObject("id", UUID.class), rs.getString("source"),
                    rs.getObject("updated_at", OffsetDateTime.class).toInstant());
        }
    }
}
