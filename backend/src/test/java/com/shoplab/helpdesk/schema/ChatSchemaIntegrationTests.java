package com.shoplab.helpdesk.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.support.PostgresTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The conversation turns and answer feedback live in the chat schema (specs/LAB-24, specs/LAB-33). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class ChatSchemaIntegrationTests {

    private static final String INSERT = "INSERT INTO chat.conversation_turn (conversation_id, question, answer)"
            + " VALUES (?, ?, ?)";
    private static final String INSERT_RETURNING_ID =
            "INSERT INTO chat.conversation_turn (conversation_id, question, answer) VALUES (?, ?, ?) RETURNING id";
    private static final String INSERT_FEEDBACK =
            "INSERT INTO chat.feedback (turn_ref, rating, comment) VALUES (?, ?, ?)";

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestTurns() {
        jdbc.update("DELETE FROM chat.conversation_turn WHERE question LIKE 'schema-test%'");
    }

    @Test
    void theTurnTableHasTheColumnsOfTheDataModel() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'chat' AND table_name = 'conversation_turn'
                """);

        assertThat(columns).extracting(column -> column.get("column_name"))
                .containsExactlyInAnyOrder(
                        "id", "conversation_id", "question", "answer", "created_at",
                        "turn_id", "delivered_documents", "trace_id");
        assertThat(typeOf(columns, "id")).isEqualTo("bigint");
        assertThat(typeOf(columns, "conversation_id")).isEqualTo("uuid");
        assertThat(typeOf(columns, "question")).isEqualTo("text");
        assertThat(typeOf(columns, "answer")).isEqualTo("text");
        assertThat(typeOf(columns, "created_at")).isEqualTo("timestamp with time zone");
        assertThat(typeOf(columns, "turn_id")).isEqualTo("uuid");
        assertThat(typeOf(columns, "delivered_documents")).isEqualTo("ARRAY");
        assertThat(typeOf(columns, "trace_id")).isEqualTo("text");
        assertThat(nullableOf(columns, "trace_id")).isEqualTo("YES");
        assertThat(columns).filteredOn(column -> !"trace_id".equals(column.get("column_name")))
                .allSatisfy(column -> assertThat(column.get("is_nullable")).isEqualTo("NO"));
    }

    @Test
    void theFeedbackTableHasTheColumnsOfTheDataModel() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'chat' AND table_name = 'feedback'
                """);

        assertThat(columns).extracting(column -> column.get("column_name"))
                .containsExactlyInAnyOrder(
                        "id", "turn_ref", "rating", "comment", "created_at", "updated_at",
                        "promoted_case_id", "promoted_case_jsonl", "promoted_at");
        assertThat(typeOf(columns, "id")).isEqualTo("bigint");
        assertThat(typeOf(columns, "turn_ref")).isEqualTo("bigint");
        assertThat(typeOf(columns, "rating")).isEqualTo("text");
        assertThat(typeOf(columns, "comment")).isEqualTo("text");
        assertThat(typeOf(columns, "created_at")).isEqualTo("timestamp with time zone");
        assertThat(typeOf(columns, "updated_at")).isEqualTo("timestamp with time zone");
        assertThat(typeOf(columns, "promoted_case_id")).isEqualTo("text");
        assertThat(typeOf(columns, "promoted_case_jsonl")).isEqualTo("text");
        assertThat(typeOf(columns, "promoted_at")).isEqualTo("timestamp with time zone");
    }

    private static Object typeOf(List<Map<String, Object>> columns, String name) {
        return columns.stream().filter(column -> name.equals(column.get("column_name")))
                .findFirst().orElseThrow().get("data_type");
    }

    private static Object nullableOf(List<Map<String, Object>> columns, String name) {
        return columns.stream().filter(column -> name.equals(column.get("column_name")))
                .findFirst().orElseThrow().get("is_nullable");
    }

    @Test
    void theIdentityGivesTheOrderOfTheTurns() {
        UUID conversation = UUID.randomUUID();
        jdbc.update(INSERT, conversation, "schema-test one", "first");
        jdbc.update(INSERT, conversation, "schema-test two", "second");

        List<String> newestFirst = jdbc.queryForList(
                "SELECT question FROM chat.conversation_turn WHERE conversation_id = ? ORDER BY id DESC",
                String.class, conversation);

        assertThat(newestFirst).containsExactly("schema-test two", "schema-test one");
    }

    @Test
    void anEmptyQuestionOrAnswerIsRejected() {
        UUID conversation = UUID.randomUUID();

        assertThatThrownBy(() -> jdbc.update(INSERT, conversation, "", "answer"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(INSERT, conversation, "schema-test question", ""))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void nothingMayBeNull() {
        assertThatThrownBy(() -> jdbc.update(INSERT, null, "schema-test question", "answer"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(INSERT, UUID.randomUUID(), null, "answer"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(INSERT, UUID.randomUUID(), "schema-test question", null))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void aTurnTraceIdMustBe32LowercaseHexCharactersOrNull() {
        UUID conversation = UUID.randomUUID();

        assertThatCode(() -> jdbc.update(
                "INSERT INTO chat.conversation_turn (conversation_id, question, answer, trace_id) VALUES (?, ?, ?, ?)",
                conversation, "schema-test trace ok", "answer", "4bf92f3577b34da6a3ce929d0e0e4736"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO chat.conversation_turn (conversation_id, question, answer, trace_id) VALUES (?, ?, ?, ?)",
                conversation, "schema-test trace bad", "answer", "not-a-hex-trace-id"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void feedbackEnforcesItsRatingCommentAndPromotionConstraints() {
        long turnId = jdbc.queryForObject(
                INSERT_RETURNING_ID, Long.class, UUID.randomUUID(), "schema-test fb", "answer");

        assertThatThrownBy(() -> jdbc.update(INSERT_FEEDBACK, turnId, "SIDEWAYS", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(INSERT_FEEDBACK, turnId, "DOWN", "   "))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(INSERT_FEEDBACK, turnId, "DOWN", "x".repeat(501)))
                .isInstanceOf(DataAccessException.class);

        jdbc.update(INSERT_FEEDBACK, turnId, "UP", "Helpful");
        assertThatThrownBy(() -> jdbc.update(INSERT_FEEDBACK, turnId, "DOWN", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE chat.feedback
                SET promoted_case_id = 'G121', promoted_case_jsonl = '{"id":"G121"}', promoted_at = now()
                WHERE turn_ref = ?
                """, turnId)).isInstanceOf(DataAccessException.class);

        jdbc.update("UPDATE chat.feedback SET rating = 'DOWN' WHERE turn_ref = ?", turnId);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE chat.feedback SET promoted_case_id = 'G121' WHERE turn_ref = ?", turnId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE chat.feedback
                SET promoted_case_id = 'BAD', promoted_case_jsonl = '{"id":"BAD"}', promoted_at = now()
                WHERE turn_ref = ?
                """, turnId)).isInstanceOf(DataAccessException.class);
        assertThatCode(() -> jdbc.update("""
                UPDATE chat.feedback
                SET promoted_case_id = 'G121', promoted_case_jsonl = '{"id":"G121"}', promoted_at = now()
                WHERE turn_ref = ?
                """, turnId)).doesNotThrowAnyException();
    }

    @Test
    void theNewestTurnsOfAConversationAreServedByItsIndex() {
        List<String> indexes = jdbc.queryForList("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'chat' AND tablename = 'conversation_turn'
                  AND indexname = 'conversation_turn_conversation_idx'
                """, String.class);

        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0)).contains("conversation_id").contains("id DESC");
    }

    @Test
    void theFeedbackListIsServedByItsRatingAndCreatedIndex() {
        List<String> indexes = jdbc.queryForList("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'chat' AND tablename = 'feedback'
                  AND indexname = 'feedback_rating_created_idx'
                """, String.class);

        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0)).contains("rating").contains("created_at DESC").contains("id DESC");
    }

    @Test
    void theTableHasNoForeignKeyToAnotherSchema() {
        List<String> foreignKeys = jdbc.queryForList("""
                SELECT c.conname FROM pg_constraint c
                WHERE c.contype = 'f' AND c.conrelid = 'chat.conversation_turn'::regclass
                """, String.class);

        assertThat(foreignKeys).isEmpty();
    }
}
