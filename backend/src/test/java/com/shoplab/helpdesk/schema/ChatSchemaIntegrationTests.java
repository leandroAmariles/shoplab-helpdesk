package com.shoplab.helpdesk.schema;

import static org.assertj.core.api.Assertions.assertThat;
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

/** The conversation turns live in the chat schema with the shape of the data model (specs/LAB-24). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class ChatSchemaIntegrationTests {

    private static final String INSERT = "INSERT INTO chat.conversation_turn (conversation_id, question, answer)"
            + " VALUES (?, ?, ?)";

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
                .containsExactlyInAnyOrder("id", "conversation_id", "question", "answer", "created_at");
        assertThat(typeOf(columns, "id")).isEqualTo("bigint");
        assertThat(typeOf(columns, "conversation_id")).isEqualTo("uuid");
        assertThat(typeOf(columns, "question")).isEqualTo("text");
        assertThat(typeOf(columns, "answer")).isEqualTo("text");
        assertThat(typeOf(columns, "created_at")).isEqualTo("timestamp with time zone");
        assertThat(columns).allSatisfy(column -> assertThat(column.get("is_nullable")).isEqualTo("NO"));
    }

    private static Object typeOf(List<Map<String, Object>> columns, String name) {
        return columns.stream().filter(column -> name.equals(column.get("column_name")))
                .findFirst().orElseThrow().get("data_type");
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
    void theTableHasNoForeignKeyToAnotherSchema() {
        List<String> foreignKeys = jdbc.queryForList("""
                SELECT c.conname FROM pg_constraint c
                WHERE c.contype = 'f' AND c.conrelid = 'chat.conversation_turn'::regclass
                """, String.class);

        assertThat(foreignKeys).isEmpty();
    }
}
