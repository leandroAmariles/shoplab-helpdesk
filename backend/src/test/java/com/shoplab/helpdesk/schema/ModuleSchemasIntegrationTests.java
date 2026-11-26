package com.shoplab.helpdesk.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.shared.infrastructure.out.database.ModuleSchema;
import com.shoplab.helpdesk.shared.infrastructure.out.database.ModuleSchemaMigrator;
import com.shoplab.helpdesk.support.PostgresTest;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The remaining module ({@code eval}) owns one schema with its own history, and no foreign key crosses schemas. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class ModuleSchemasIntegrationTests {

    private static final String CROSS_SCHEMA_FOREIGN_KEYS = """
            SELECT c.conname, src.nspname AS from_schema, dst.nspname AS to_schema
            FROM pg_constraint c
            JOIN pg_class srcc ON srcc.oid = c.conrelid
            JOIN pg_namespace src ON src.oid = srcc.relnamespace
            JOIN pg_class dstc ON dstc.oid = c.confrelid
            JOIN pg_namespace dst ON dst.oid = dstc.relnamespace
            WHERE c.contype = 'f' AND src.nspname <> dst.nspname
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private List<ModuleSchema> schemas;

    @Test
    void evalHasItsSchemaWithItsOwnAppliedHistory() {
        List<String> existing = jdbc.queryForList("SELECT schema_name FROM information_schema.schemata", String.class);

        assertThat(existing).contains("eval");
        assertThat(appliedVersionsOf("eval")).containsExactly("1", "2");
    }

    @Test
    void startingAgainKeepsEvalHistoryIdempotent() {
        Integer before = jdbc.queryForObject(
                "SELECT count(*) FROM eval.flyway_schema_history WHERE success", Integer.class);

        new ModuleSchemaMigrator(schemas, dataSource).migrate();

        Integer after = jdbc.queryForObject(
                "SELECT count(*) FROM eval.flyway_schema_history WHERE success", Integer.class);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void noForeignKeyCrossesSchemas() {
        assertThat(jdbc.queryForList(CROSS_SCHEMA_FOREIGN_KEYS)).isEmpty();
    }

    @Test
    void theCheckSeesAForeignKeyThatCrossesSchemas() {
        try {
            jdbc.execute("CREATE SCHEMA tmp_parent");
            jdbc.execute("CREATE SCHEMA tmp_child");
            jdbc.execute("CREATE TABLE tmp_parent.owner (id BIGINT PRIMARY KEY)");
            jdbc.execute("CREATE TABLE tmp_child.item (owner_id BIGINT REFERENCES tmp_parent.owner (id))");

            List<Map<String, Object>> found = jdbc.queryForList(CROSS_SCHEMA_FOREIGN_KEYS);

            assertThat(found).hasSize(1);
            assertThat(found.get(0)).containsEntry("from_schema", "tmp_child").containsEntry("to_schema", "tmp_parent");
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS tmp_child CASCADE");
            jdbc.execute("DROP SCHEMA IF EXISTS tmp_parent CASCADE");
        }
    }

    private List<String> appliedVersionsOf(String module) {
        return jdbc.queryForList("SELECT version FROM " + module + ".flyway_schema_history "
                + "WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class);
    }
}
