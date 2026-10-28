package com.shoplab.helpdesk.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.shared.infrastructure.out.database.ModuleSchema;
import com.shoplab.helpdesk.shared.infrastructure.out.database.ModuleSchemaMigrator;
import com.shoplab.helpdesk.support.PostgresTest;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    private static final Map<String, List<String>> OLIST_TABLES = Map.of(
            "orders", List.of("customers", "orders", "order_items", "order_payments", "order_reviews"),
            "catalog", List.of("product_categories", "products", "sellers"));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private List<ModuleSchema> schemas;

    private List<String> tablesOf(String schema) {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?", String.class, schema);
    }

    private List<Integer> historySizes(String... modules) {
        return Stream.of(modules)
                .map(module -> jdbc.queryForObject(
                        "SELECT count(*) FROM " + module + ".flyway_schema_history WHERE success", Integer.class))
                .toList();
    }

    /** The foreign keys declared in {@code schema}, as {@code child->parent} table names. */
    private List<String> foreignKeysOf(String schema) {
        return jdbc.queryForList("""
                SELECT child.relname || '->' || parent.relname
                FROM pg_constraint c
                JOIN pg_class child ON child.oid = c.conrelid
                JOIN pg_class parent ON parent.oid = c.confrelid
                JOIN pg_namespace ns ON ns.oid = child.relnamespace
                WHERE c.contype = 'f' AND ns.nspname = ?
                """, String.class, schema);
    }

    private List<String> checkDefinitionsOf(String qualifiedTable) {
        return jdbc.queryForList("""
                SELECT pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                WHERE c.contype = 'c' AND c.conrelid = ?::regclass
                """, String.class, qualifiedTable);
    }

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
    void theOlistTablesExistInTheSchemaOfTheirOwnerAndOnlyThere() {
        for (Map.Entry<String, List<String>> owner : OLIST_TABLES.entrySet()) {
            assertThat(tablesOf(owner.getKey())).as("tables of %s", owner.getKey())
                    .containsAll(owner.getValue());
            for (String other : MODULES) {
                if (!other.equals(owner.getKey())) {
                    assertThat(tablesOf(other)).as("tables of %s", other)
                            .doesNotContainAnyElementsOf(owner.getValue());
                }
            }
        }
    }

    @Test
    void startingAgainKeepsTheHistoryOfOrdersAndCatalog() {
        List<Integer> before = historySizes("orders", "catalog");

        new ModuleSchemaMigrator(schemas, dataSource).migrate();

        assertThat(historySizes("orders", "catalog")).isEqualTo(before);
        for (String module : List.of("orders", "catalog")) {
            Integer applied = jdbc.queryForObject(
                    "SELECT count(*) FROM " + module + ".flyway_schema_history WHERE version = '2' AND success",
                    Integer.class);
            assertThat(applied).as("tables migration of %s applied once", module).isEqualTo(1);
        }
    }

    @Test
    void theOlistTablesKeepTheirRelationshipsInsideTheirOwnSchema() {
        assertThat(foreignKeysOf("orders")).contains(
                "orders->customers", "order_items->orders", "order_payments->orders", "order_reviews->orders");
        assertThat(foreignKeysOf("catalog")).contains("products->product_categories");
    }

    @ParameterizedTest
    @CsvSource({
        "orders.order_items, price", "orders.order_items, freight_value", "orders.order_payments, payment_value",
        "orders.order_payments, installments", "catalog.products, weight_g", "catalog.products, length_cm",
        "catalog.products, height_cm", "catalog.products, width_cm", "catalog.products, reference_price",
        "catalog.products, stock"})
    void numericOlistColumnsRejectNegativeValues(String table, String column) {
        assertThat(checkDefinitionsOf(table)).anySatisfy(
                definition -> assertThat(definition).containsPattern("\\b" + column + " >= \\(?0\\b"));
    }

    @Test
    void aReviewScoreMustBeBetweenOneAndFive() {
        assertThat(checkDefinitionsOf("orders.order_reviews")).anySatisfy(
                definition -> assertThat(definition).containsPattern("score >= 1.*score <= 5"));
    }

    @Test
    void anOrderItemReferencesItsOrderAndNothingElse() {
        assertThat(foreignKeysOf("orders")).filteredOn(key -> key.startsWith("order_items->"))
                .containsExactly("order_items->orders");
    }

    @Test
    void anOrderItemWhoseProductAndSellerAreNotInTheCatalogIsAccepted() {
        try {
            jdbc.update("INSERT INTO orders.customers (customer_id, customer_unique_id) VALUES ('tmp-c', 'tmp-u')");
            jdbc.update(
                    "INSERT INTO orders.orders (order_id, customer_id, status) VALUES ('tmp-o', 'tmp-c', 'created')");

            int inserted = jdbc.update("""
                    INSERT INTO orders.order_items (order_id, item_number, product_id, seller_id, price, freight_value)
                    VALUES ('tmp-o', 1, 'no-such-product', 'no-such-seller', 1.00, 0.00)
                    """);

            assertThat(inserted).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM orders.order_items WHERE order_id = 'tmp-o'");
            jdbc.update("DELETE FROM orders.orders WHERE order_id = 'tmp-o'");
            jdbc.update("DELETE FROM orders.customers WHERE customer_id = 'tmp-c'");
        }
    }

    @Test
    void theCheckSeesAForeignKeyFromAnOrderItemToTheCatalog() {
        try {
            jdbc.execute("ALTER TABLE orders.order_items ADD CONSTRAINT tmp_item_product_fk "
                    + "FOREIGN KEY (product_id) REFERENCES catalog.products (product_id)");

            List<Map<String, Object>> found = jdbc.queryForList(CROSS_SCHEMA_FOREIGN_KEYS);

            assertThat(found).hasSize(1);
            assertThat(found.get(0)).containsEntry("from_schema", "orders").containsEntry("to_schema", "catalog");
        } finally {
            jdbc.execute("ALTER TABLE orders.order_items DROP CONSTRAINT IF EXISTS tmp_item_product_fk");
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

    @Test
    void retrievalForeignKeysStayInsideItsSchema() {
        assertThat(foreignKeysOf("retrieval")).containsExactly("chunk->document");
    }

    @Test
    void retrievalHistoryHasTheBaselineAndTheKnowledgeBaseTablesAppliedOnce() {
        jdbc.update("INSERT INTO retrieval.document (source, title) VALUES ('test/history.md', 'History')");
        try {
            List<String> applied = appliedVersionsOf("retrieval");
            List<Integer> before = historySizes("retrieval");

            new ModuleSchemaMigrator(schemas, dataSource).migrate();

            assertThat(applied).containsExactly("1", "2", "3");
            assertThat(historySizes("retrieval")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval.document WHERE source = 'test/history.md'",
                    Integer.class)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM retrieval.document WHERE source = 'test/history.md'");
        }
    }

    @Test
    void theOtherModulesKeepTheirHistory() {
        assertThat(appliedVersionsOf("chat")).containsExactly("1");
        assertThat(appliedVersionsOf("tickets")).containsExactly("1");
        assertThat(appliedVersionsOf("orders")).containsExactly("1", "2");
        assertThat(appliedVersionsOf("catalog")).containsExactly("1", "2");
    }

    private List<String> appliedVersionsOf(String module) {
        return jdbc.queryForList("SELECT version FROM " + module + ".flyway_schema_history "
                + "WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class);
    }
}
