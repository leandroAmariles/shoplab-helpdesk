package com.shoplab.helpdesk.retrieval.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.retrieval.domain.model.DocumentSummary;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexStatus;
import com.shoplab.helpdesk.support.PostgresTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The list of documents shows the state stored by retrieval and the chunks really stored (specs/LAB-25, FR-005/6). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class JdbcDocumentCatalogAdapterTests {

    private static final String PREFIX = "catalog-test/";

    @Autowired
    private JdbcDocumentCatalogAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestDocuments() {
        jdbc.update("DELETE FROM retrieval.document WHERE source LIKE ?", PREFIX + "%");
    }

    private UUID insert(String name, String status, String error, Instant updatedAt, int chunks, int withVector) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO retrieval.document (source, title, metadata, index_status, index_error, indexed_at,
                                                updated_at)
                VALUES (?, ?, '{}'::jsonb, ?, ?, CASE WHEN ? = 'INDEXED' THEN ?::timestamptz END, ?)
                RETURNING id
                """, UUID.class, PREFIX + name, "Titulo " + name, status, error, status,
                OffsetDateTime.ofInstant(updatedAt.plusSeconds(5), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC));
        String vector = "[" + "0.1,".repeat(1023) + "0.1]";
        for (int index = 0; index < chunks; index++) {
            if (index < withVector) {
                jdbc.update("""
                        INSERT INTO retrieval.chunk (document_id, chunk_index, content, metadata, embedding,
                                                     embedding_model)
                        VALUES (?, ?, 'texto', '{}'::jsonb, ?::public.vector, 'bge-m3')
                        """, id, index, vector);
            } else {
                jdbc.update("""
                        INSERT INTO retrieval.chunk (document_id, chunk_index, content, metadata)
                        VALUES (?, ?, 'texto', '{}'::jsonb)
                        """, id, index);
            }
        }
        return id;
    }

    private List<DocumentSummary> listTestDocuments() {
        List<DocumentSummary> all = adapter.listDocuments().collectList().block();
        assertThat(all).isNotNull();
        return all.stream().filter(document -> document.name().startsWith(PREFIX)).toList();
    }

    @Test
    void showsEachDocumentWithItsStateCauseDatesAndChunksStored() {
        Instant base = Instant.parse("2026-11-10T15:00:00Z");
        UUID indexed = insert("indexed.md", "INDEXED", null, base, 3, 3);
        UUID failed = insert("failed.pdf", "ERROR", "The embedding model is not available", base.minusSeconds(60),
                2, 0);
        UUID pending = insert("pending.md", "PENDING", null, base.minusSeconds(120), 4, 1);

        List<DocumentSummary> documents = listTestDocuments();

        assertThat(documents).extracting(DocumentSummary::id).containsExactly(indexed, failed, pending);
        DocumentSummary first = documents.get(0);
        assertThat(first.name()).isEqualTo(PREFIX + "indexed.md");
        assertThat(first.title()).isEqualTo("Titulo indexed.md");
        assertThat(first.status()).isEqualTo(IndexStatus.INDEXED);
        assertThat(first.chunkCount()).isEqualTo(3);
        assertThat(first.error()).isNull();
        assertThat(first.updatedAt()).isEqualTo(base);
        assertThat(first.indexedAt()).isEqualTo(base.plusSeconds(5));
        assertThat(documents.get(1).status()).isEqualTo(IndexStatus.ERROR);
        assertThat(documents.get(1).error()).isEqualTo("The embedding model is not available");
        assertThat(documents.get(1).indexedAt()).isNull();
        assertThat(documents.get(2).status()).isEqualTo(IndexStatus.PENDING);
        assertThat(documents.get(2).chunkCount()).as("chunks without vector count too").isEqualTo(4);
    }

    @Test
    void aDocumentWithoutChunksHasZero() {
        insert("empty.md", "PENDING", null, Instant.parse("2026-11-10T15:00:00Z"), 0, 0);

        assertThat(listTestDocuments()).singleElement()
                .satisfies(document -> assertThat(document.chunkCount()).isZero());
    }

    @Test
    void documentsUpdatedAtTheSameInstantKeepAStableOrderById() {
        Instant same = Instant.parse("2026-11-10T15:00:00Z");
        UUID one = insert("one.md", "PENDING", null, same, 1, 0);
        UUID two = insert("two.md", "PENDING", null, same, 1, 0);

        // PostgreSQL orders uuid by unsigned bytes, which is the order of the canonical text (UUID.compareTo is signed).
        List<UUID> expected = one.toString().compareTo(two.toString()) < 0 ? List.of(one, two) : List.of(two, one);
        assertThat(listTestDocuments()).extracting(DocumentSummary::id).containsExactlyElementsOf(expected);
        assertThat(listTestDocuments()).extracting(DocumentSummary::id).containsExactlyElementsOf(expected);
    }
}
