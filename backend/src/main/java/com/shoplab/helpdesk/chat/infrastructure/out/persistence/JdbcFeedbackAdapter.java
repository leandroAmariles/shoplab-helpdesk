package com.shoplab.helpdesk.chat.infrastructure.out.persistence;

import com.shoplab.helpdesk.chat.application.port.in.ListFeedbackQuery;
import com.shoplab.helpdesk.chat.application.port.out.FeedbackStorePort;
import com.shoplab.helpdesk.chat.domain.exception.ConversationMemoryException;
import com.shoplab.helpdesk.chat.domain.exception.FeedbackAlreadyPromotedException;
import com.shoplab.helpdesk.chat.domain.exception.FeedbackNotNegativeException;
import com.shoplab.helpdesk.chat.domain.exception.FeedbackTurnNotFoundException;
import com.shoplab.helpdesk.chat.domain.model.FeedbackItem;
import com.shoplab.helpdesk.chat.domain.model.FeedbackListing;
import com.shoplab.helpdesk.chat.domain.model.FeedbackRecord;
import com.shoplab.helpdesk.chat.domain.model.SubmitFeedbackCommand;
import com.shoplab.helpdesk.chat.domain.model.enums.FeedbackRating;
import com.shoplab.helpdesk.chat.domain.model.enums.MemoryOperation;
import com.shoplab.helpdesk.shared.infrastructure.out.blocking.BlockingCalls;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/**
 * Persists and queries customer feedback in {@code chat.feedback} joined with {@code chat.conversation_turn}. All
 * blocking JDBC work is offloaded to {@link BlockingCalls}.
 */
@Component
@DependsOn("moduleSchemaMigrator")
public class JdbcFeedbackAdapter implements FeedbackStorePort {

    private static final String LOCK_TURN_FOR_UPSERT = """
            SELECT t.id AS turn_ref, f.promoted_case_id
            FROM chat.conversation_turn t
            LEFT JOIN chat.feedback f ON f.turn_ref = t.id
            WHERE t.turn_id = ?
            FOR UPDATE OF t
            """;

    private static final String UPSERT_FEEDBACK = """
            INSERT INTO chat.feedback (turn_ref, rating, comment, created_at, updated_at)
            VALUES (?, ?, ?, now(), now())
            ON CONFLICT (turn_ref) DO UPDATE
            SET rating = EXCLUDED.rating,
                comment = EXCLUDED.comment,
                updated_at = now()
            WHERE chat.feedback.promoted_case_id IS NULL
            RETURNING rating, comment, created_at, updated_at, promoted_case_id, promoted_at
            """;

    private static final String COUNTS_BY_RATING = """
            SELECT count(*) FILTER (WHERE promoted_case_id IS NULL) AS pending_count,
                   count(*) FILTER (WHERE promoted_case_id IS NOT NULL) AS promoted_count
            FROM chat.feedback
            WHERE rating = ?
            """;

    private static final String MAX_PROMOTED_CASE_NUMBER = """
            SELECT COALESCE(MAX(CAST(SUBSTRING(promoted_case_id FROM 2) AS integer)), 0)
            FROM chat.feedback
            WHERE promoted_case_id IS NOT NULL
            """;

    private static final String SELECT_FEEDBACK_ITEMS_BASE = """
            SELECT t.turn_id, t.conversation_id, t.question, t.answer, t.delivered_documents, t.trace_id,
                   f.rating, f.comment, f.created_at, f.updated_at,
                   f.promoted_case_id, f.promoted_case_jsonl, f.promoted_at
            FROM chat.feedback f
            JOIN chat.conversation_turn t ON t.id = f.turn_ref
            WHERE f.rating = ?
            """;

    private static final String ORDER_AND_LIMIT = " ORDER BY f.created_at DESC, f.id DESC LIMIT ?";

    private static final String LOCK_FEEDBACK_FOR_PROMOTE = """
            SELECT f.id, f.rating, f.promoted_case_id
            FROM chat.conversation_turn t
            JOIN chat.feedback f ON f.turn_ref = t.id
            WHERE t.turn_id = ?
            FOR UPDATE OF f
            """;

    private static final String PROMOTE_FEEDBACK = """
            UPDATE chat.feedback
            SET promoted_case_id = ?,
                promoted_case_jsonl = ?,
                promoted_at = now(),
                updated_at = now()
            WHERE id = ? AND promoted_case_id IS NULL
            """;

    private static final String SELECT_SINGLE_ITEM = SELECT_FEEDBACK_ITEMS_BASE.replace(
            "WHERE f.rating = ?", "WHERE t.turn_id = ?");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcFeedbackAdapter(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @Override
    public Mono<FeedbackRecord> upsert(SubmitFeedbackCommand command) {
        return BlockingCalls.call(() -> transaction.execute(status -> upsertInTransaction(command)))
                .onErrorMap(
                        JdbcFeedbackAdapter::isDatabaseFailure,
                        error -> new ConversationMemoryException(MemoryOperation.WRITE, error));
    }

    @Override
    public Mono<FeedbackListing> list(ListFeedbackQuery query, int baseGoldenCaseNumber) {
        return BlockingCalls.call(() -> listSync(query, baseGoldenCaseNumber))
                .onErrorMap(
                        JdbcFeedbackAdapter::isDatabaseFailure,
                        error -> new ConversationMemoryException(MemoryOperation.READ, error));
    }

    @Override
    public Mono<FeedbackItem> promote(String turnId, String caseId, String canonicalJsonl) {
        return BlockingCalls.call(() -> transaction.execute(
                        status -> promoteInTransaction(turnId, caseId, canonicalJsonl)))
                .onErrorMap(DuplicateKeyException.class, error -> new FeedbackAlreadyPromotedException())
                .onErrorMap(
                        JdbcFeedbackAdapter::isDatabaseFailure,
                        error -> new ConversationMemoryException(MemoryOperation.WRITE, error));
    }

    private static boolean isDatabaseFailure(Throwable error) {
        return error instanceof DataAccessException || error instanceof TransactionException;
    }

    private FeedbackRecord upsertInTransaction(SubmitFeedbackCommand command) {
        UUID turnUuid = UUID.fromString(command.turnId());
        List<TurnLockRow> lockRows = jdbc.query(
                LOCK_TURN_FOR_UPSERT,
                (rs, rowNum) -> new TurnLockRow(rs.getLong("turn_ref"), rs.getString("promoted_case_id")),
                turnUuid);
        if (lockRows.isEmpty()) {
            throw new FeedbackTurnNotFoundException();
        }
        TurnLockRow lockRow = lockRows.get(0);
        if (lockRow.promotedCaseId() != null) {
            throw new FeedbackAlreadyPromotedException();
        }
        List<FeedbackRecord> records = jdbc.query(
                UPSERT_FEEDBACK,
                (rs, rowNum) -> new FeedbackRecord(
                        command.turnId(),
                        FeedbackRating.valueOf(rs.getString("rating")),
                        rs.getString("comment"),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("updated_at")),
                        rs.getString("promoted_case_id"),
                        toInstant(rs.getTimestamp("promoted_at"))),
                lockRow.turnRef(),
                command.rating().name(),
                command.comment());
        if (records.isEmpty()) {
            throw new FeedbackAlreadyPromotedException();
        }
        return records.get(0);
    }

    private FeedbackListing listSync(ListFeedbackQuery query, int baseGoldenCaseNumber) {
        String rating = query.rating().name();
        CountsRow counts = jdbc.queryForObject(
                COUNTS_BY_RATING,
                (rs, rowNum) -> new CountsRow(rs.getInt("pending_count"), rs.getInt("promoted_count")),
                rating);
        int pendingCount = counts == null ? 0 : counts.pendingCount();
        int promotedCount = counts == null ? 0 : counts.promotedCount();
        int total = computeTotal(query.promoted(), pendingCount, promotedCount);
        int maxPromoted = jdbc.queryForObject(MAX_PROMOTED_CASE_NUMBER, Integer.class);
        int nextCaseNumber = Math.max(baseGoldenCaseNumber, maxPromoted) + 1;
        String suggestedNextCaseId = String.format(Locale.ROOT, "G%03d", nextCaseNumber);

        String sql = buildListSql(query.promoted());
        List<FeedbackItem> items = jdbc.query(sql, (rs, rowNum) -> mapFeedbackItem(rs), rating, query.limit());
        return new FeedbackListing(total, pendingCount, promotedCount, suggestedNextCaseId, items);
    }

    private FeedbackItem promoteInTransaction(String turnId, String caseId, String canonicalJsonl) {
        UUID turnUuid = UUID.fromString(turnId);
        List<FeedbackLockRow> rows = jdbc.query(
                LOCK_FEEDBACK_FOR_PROMOTE,
                (rs, rowNum) -> new FeedbackLockRow(
                        rs.getLong("id"), rs.getString("rating"), rs.getString("promoted_case_id")),
                turnUuid);
        if (rows.isEmpty()) {
            throw new FeedbackTurnNotFoundException();
        }
        FeedbackLockRow row = rows.get(0);
        if (row.promotedCaseId() != null) {
            throw new FeedbackAlreadyPromotedException();
        }
        if (!FeedbackRating.DOWN.name().equals(row.rating())) {
            throw new FeedbackNotNegativeException();
        }
        int updated = jdbc.update(PROMOTE_FEEDBACK, caseId, canonicalJsonl, row.id());
        if (updated == 0) {
            throw new FeedbackAlreadyPromotedException();
        }
        return jdbc.queryForObject(SELECT_SINGLE_ITEM, (rs, rowNum) -> mapFeedbackItem(rs), turnUuid);
    }

    private static int computeTotal(Boolean promotedFilter, int pendingCount, int promotedCount) {
        if (promotedFilter == null) {
            return pendingCount + promotedCount;
        }
        return promotedFilter ? promotedCount : pendingCount;
    }

    private static String buildListSql(Boolean promotedFilter) {
        if (promotedFilter == null) {
            return SELECT_FEEDBACK_ITEMS_BASE + ORDER_AND_LIMIT;
        }
        if (promotedFilter) {
            return SELECT_FEEDBACK_ITEMS_BASE + " AND f.promoted_case_id IS NOT NULL" + ORDER_AND_LIMIT;
        }
        return SELECT_FEEDBACK_ITEMS_BASE + " AND f.promoted_case_id IS NULL" + ORDER_AND_LIMIT;
    }

    private static FeedbackItem mapFeedbackItem(ResultSet rs) throws SQLException {
        return new FeedbackItem(
                rs.getObject("turn_id", UUID.class).toString(),
                rs.getObject("conversation_id", UUID.class).toString(),
                rs.getString("question"),
                rs.getString("answer"),
                readTextArray(rs.getArray("delivered_documents")),
                rs.getString("trace_id"),
                FeedbackRating.valueOf(rs.getString("rating")),
                rs.getString("comment"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getString("promoted_case_id"),
                rs.getString("promoted_case_jsonl"),
                toInstant(rs.getTimestamp("promoted_at")));
    }

    private static List<String> readTextArray(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        try {
            Object raw = sqlArray.getArray();
            if (raw instanceof String[] strings) {
                return List.copyOf(Arrays.asList(strings));
            }
            if (raw instanceof Object[] objects) {
                return Arrays.stream(objects).map(String::valueOf).toList();
            }
            return List.of();
        } finally {
            sqlArray.free();
        }
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record TurnLockRow(long turnRef, String promotedCaseId) {
    }

    private record CountsRow(int pendingCount, int promotedCount) {
    }

    private record FeedbackLockRow(long id, String rating, String promotedCaseId) {
    }
}
