package com.shoplab.helpdesk.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.support.PostgresTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Each module owns one schema with its own history, and no foreign key crosses schemas (specs/LAB-105, S4/S5). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class ModuleSchemasIntegrationTests {

    private static final List<String> MODULES = List.of("chat", "retrieval", "orders", "catalog", "tickets");

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

    @Test
    void everyModuleHasItsSchemaWithItsOwnAppliedBaseline() {
        List<String> schemas = jdbc.queryForList("SELECT schema_name FROM information_schema.schemata",
                String.class);

        assertThat(schemas).containsAll(MODULES);
        for (String module : MODULES) {
            Integer applied = jdbc.queryForObject(
                    "SELECT count(*) FROM " + module + ".flyway_schema_history WHERE version = '1' AND success",
                    Integer.class);
            assertThat(applied).as("baseline of %s", module).isEqualTo(1);
        }
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
}
