package com.shoplab.helpdesk.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.support.PostgresTest;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The documents and vectorized chunks of the knowledge base live in the retrieval schema (specs/LAB-19). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class RetrievalSchemaIntegrationTests {

    private static final Logger LOG = LoggerFactory.getLogger(RetrievalSchemaIntegrationTests.class);
    private static final int DIMENSIONS = 1024;
    private static final String MODEL = "bge-m3";
    private static final String INSERT_CHUNK = """
            INSERT INTO retrieval.chunk (document_id, chunk_index, content, embedding, embedding_model, metadata)
            VALUES (?, ?, ?, ?::public.vector, ?, ?::jsonb)
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Removes the rows of the test and vacuums: an HNSW index keeps the entries of deleted rows until a vacuum, and a
     * search that walks through them can return fewer rows than exist. Production relies on autovacuum.
     */
    @AfterEach
    void removeTestDocuments() {
        jdbc.update("DELETE FROM retrieval.document WHERE source LIKE 'test/%'");
        jdbc.execute("VACUUM retrieval.chunk");
    }

    /** A vector literal whose first values are {@code leading} and the rest zeros up to 1024 dimensions. */
    private static String vector(double... leading) {
        return IntStream.range(0, DIMENSIONS)
                .mapToObj(index -> String.valueOf(index < leading.length ? leading[index] : 0.0))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String constantVector(int dimensions, double value) {
        return IntStream.range(0, dimensions).mapToObj(index -> String.valueOf(value))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private UUID insertDocument(String source, String title) {
        return jdbc.queryForObject(
                "INSERT INTO retrieval.document (source, title) VALUES (?, ?) RETURNING id",
                UUID.class, source, title);
    }

    private void insertChunk(UUID documentId, int chunkIndex, String content, String embedding, String model,
            String metadata) {
        jdbc.update(INSERT_CHUNK, documentId, chunkIndex, content, embedding, model, metadata);
    }

    private void insertChunk(UUID documentId, int chunkIndex, String content, String embedding) {
        insertChunk(documentId, chunkIndex, content, embedding, MODEL, "{}");
    }

    private int countChunksOf(UUID documentId) {
        return jdbc.queryForObject("SELECT count(*) FROM retrieval.chunk WHERE document_id = ?", Integer.class,
                documentId);
    }

    @Test
    void theKnowledgeBaseTablesExistInTheRetrievalSchema() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'retrieval'", String.class);

        assertThat(tables).contains("document", "chunk");
    }

    @Test
    void aDocumentWithItsChunksIsReadBackIntact() {
        UUID document = insertDocument("test/read-back.md", "Read back");
        for (int index = 0; index < 100; index++) {
            insertChunk(document, index, "chunk text " + index, vector(1.0, index + 1.0), MODEL,
                    "{\"topic\":\"returns\",\"n\":" + index + "}");
        }

        List<String> rows = jdbc.queryForList("""
                SELECT chunk_index || '|' || content || '|' || embedding_model || '|' || (metadata ->> 'topic')
                       || '|' || (metadata ->> 'n')
                FROM retrieval.chunk WHERE document_id = ? ORDER BY chunk_index
                """, String.class, document);

        assertThat(rows).hasSize(100);
        for (int index = 0; index < 100; index++) {
            assertThat(rows.get(index)).isEqualTo(index + "|chunk text " + index + "|bge-m3|returns|" + index);
            Boolean sameVector = jdbc.queryForObject(
                    "SELECT embedding = ?::public.vector FROM retrieval.chunk WHERE document_id = ? "
                            + "AND chunk_index = ?", Boolean.class, vector(1.0, index + 1.0), document, index);
            assertThat(sameVector).as("vector of chunk %s", index).isTrue();
        }
    }

    @Test
    void aVectorOfAnotherDimensionIsRejected() {
        UUID document = insertDocument("test/dimension.md", "Dimension");

        assertThatThrownBy(() -> insertChunk(document, 0, "text", constantVector(3, 1.0)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("expected 1024 dimensions");
        assertThat(countChunksOf(document)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nullModel", "blankModel", "nullContent", "blankContent", "nullDocument",
        "unknownDocument"})
    void invalidChunksAreRejectedByTheDatabase(String invalid) {
        UUID document = insertDocument("test/invalid-" + invalid + ".md", "Invalid");
        UUID owner = switch (invalid) {
            case "nullDocument" -> null;
            case "unknownDocument" -> UUID.randomUUID();
            default -> document;
        };
        String model = switch (invalid) {
            case "nullModel" -> null;
            case "blankModel" -> "   ";
            default -> MODEL;
        };
        String content = switch (invalid) {
            case "nullContent" -> null;
            case "blankContent" -> "  \n ";
            default -> "text";
        };

        assertThatThrownBy(() -> insertChunk(owner, 0, content, vector(1.0), model, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countChunksOf(document)).isZero();
    }

    /** V3 (specs/LAB-20) relaxes LAB-19 FR-003: a chunk may wait for its vector, but never half of the pair. */
    @Test
    void aChunkWithoutVectorAndWithoutModelIsAccepted() {
        UUID document = insertDocument("test/pending.md", "Pending");

        insertChunk(document, 0, "waiting for its embedding", null, null, "{\"section\":\"A\"}");

        assertThat(jdbc.queryForObject(
                "SELECT embedding IS NULL AND embedding_model IS NULL FROM retrieval.chunk WHERE document_id = ?",
                Boolean.class, document)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"vectorWithoutModel", "modelWithoutVector"})
    void halfOfTheEmbeddingPairIsRejected(String half) {
        UUID document = insertDocument("test/half-" + half + ".md", "Half");
        String embedding = "vectorWithoutModel".equals(half) ? vector(1.0) : null;
        String model = "vectorWithoutModel".equals(half) ? null : MODEL;

        assertThatThrownBy(() -> insertChunk(document, 0, "text", embedding, model, "{}"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chunk_embedding_pair_chk");
        assertThat(countChunksOf(document)).isZero();
    }

    @Test
    void aChunkWithoutVectorDoesNotTakePartInTheNearestSearch() {
        UUID document = insertDocument("test/search-pending.md", "Search pending");
        insertChunk(document, 0, "indexed", vector(1.0));
        insertChunk(document, 1, "pending", null, null, "{}");

        List<String> nearest = jdbc.queryForList("""
                SELECT content FROM retrieval.chunk
                WHERE embedding IS NOT NULL AND document_id = ?
                ORDER BY embedding <=> ?::public.vector LIMIT 5
                """, String.class, document, vector(1.0));

        assertThat(nearest).containsExactly("indexed");
    }

    @Test
    void twoChunksCannotShareAPositionInTheSameDocument() {
        UUID document = insertDocument("test/position.md", "Position");
        insertChunk(document, 0, "first", vector(1.0));

        assertThatThrownBy(() -> insertChunk(document, 0, "again", vector(1.0)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertChunk(document, -1, "negative", vector(1.0)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countChunksOf(document)).isEqualTo(1);
    }

    @Test
    void deletingADocumentDeletesItsChunks() {
        UUID document = insertDocument("test/cascade.md", "Cascade");
        insertChunk(document, 0, "first", vector(1.0));
        insertChunk(document, 1, "second", vector(0.0, 1.0));

        jdbc.update("DELETE FROM retrieval.document WHERE id = ?", document);

        assertThat(countChunksOf(document)).isZero();
    }

    @Test
    void metadataDefaultsToAnEmptyObjectAndMustBeAnObject() {
        UUID document = insertDocument("test/metadata.md", "Metadata");
        insertChunk(document, 0, "text", vector(1.0));

        assertThat(jdbc.queryForObject("SELECT metadata::text FROM retrieval.document WHERE id = ?", String.class,
                document)).isEqualTo("{}");
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM retrieval.chunk WHERE document_id = ?",
                String.class, document)).isEqualTo("{}");
        for (String notAnObject : List.of("[]", "1", "\"text\"", "null")) {
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE retrieval.document SET metadata = ?::jsonb WHERE id = ?", notAnObject, document))
                    .as("document metadata %s", notAnObject)
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE retrieval.chunk SET metadata = ?::jsonb WHERE document_id = ?", notAnObject, document))
                    .as("chunk metadata %s", notAnObject)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE retrieval.chunk SET metadata = NULL WHERE document_id = ?",
                document)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSourceCanOnlyBeRegisteredOnce() {
        insertDocument("test/unique.md", "First");

        assertThatThrownBy(() -> insertDocument("test/unique.md", "Second"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aDocumentWithoutChunksIsValid() {
        UUID document = insertDocument("test/empty.md", "Empty");

        assertThat(countChunksOf(document)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval.document WHERE id = ?", Integer.class,
                document)).isEqualTo(1);
    }

    @Test
    void chunksCanBeFilteredByAMetadataAttribute() {
        UUID document = insertDocument("test/filter.md", "Filter");
        insertChunk(document, 0, "returns", vector(1.0), MODEL, "{\"topic\":\"returns\"}");
        insertChunk(document, 1, "shipping", vector(0.0, 1.0), MODEL, "{\"topic\":\"shipping\"}");

        List<String> found = jdbc.queryForList(
                "SELECT content FROM retrieval.chunk WHERE metadata @> '{\"topic\":\"returns\"}'", String.class);

        assertThat(found).containsExactly("returns");
    }

    @Test
    void chunkHasAnHnswIndexOnTheEmbeddingWithCosineDistance() {
        assertThat(indexDefinition("chunk_embedding_hnsw_idx")).contains("USING hnsw", "vector_cosine_ops");
    }

    @Test
    void chunkHasAGinIndexOnItsMetadata() {
        assertThat(indexDefinition("chunk_metadata_gin_idx")).contains("USING gin", "jsonb_path_ops");
    }

    private String indexDefinition(String name) {
        List<String> definitions = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'retrieval' AND tablename = 'chunk' "
                        + "AND indexname = ?", String.class, name);
        assertThat(definitions).as("index %s", name).hasSize(1);
        return definitions.get(0);
    }

    @Test
    void nearestChunksComeOrderedByCosineDistance() {
        UUID document = insertDocument("test/nearest.md", "Nearest");
        insertChunk(document, 0, "far", vector(0.0, 1.0));
        insertChunk(document, 1, "near", vector(1.0, 0.0));
        insertChunk(document, 2, "middle", vector(1.0, 1.0));

        List<String> nearest = jdbc.queryForList(
                "SELECT content FROM retrieval.chunk ORDER BY embedding <=> ?::public.vector LIMIT 3",
                String.class, vector(1.0, 0.0));

        assertThat(nearest).containsExactly("near", "middle", "far");
    }

    @Test
    void fiveNearestOutOfTenThousandUseTheIndexInUnderOneSecond() {
        UUID document = insertDocument("test/performance.md", "Performance");
        jdbc.update("""
                INSERT INTO retrieval.chunk (document_id, chunk_index, content, embedding, embedding_model)
                SELECT ?, g, 'chunk ' || g,
                       (SELECT array_agg(random()::real) FROM generate_series(1, 1024) WHERE g > 0)::public.vector,
                       'bge-m3'
                FROM generate_series(1, 10000) AS g
                """, document);
        jdbc.execute("ANALYZE retrieval.chunk");
        String query = constantVector(DIMENSIONS, 0.5);
        String sql = "SELECT id FROM retrieval.chunk ORDER BY embedding <=> '" + query + "'::public.vector LIMIT 5";

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            String plan = String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
            jdbc.queryForList(sql, UUID.class);
            long start = System.nanoTime();
            List<UUID> nearest = jdbc.queryForList(sql, UUID.class);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            LOG.info("5 nearest of 10000 chunks took {} ms", elapsedMillis);

            assertThat(plan).contains("chunk_embedding_hnsw_idx");
            assertThat(nearest).hasSize(5);
            assertThat(elapsedMillis).isLessThan(1000);
        });
    }
}
