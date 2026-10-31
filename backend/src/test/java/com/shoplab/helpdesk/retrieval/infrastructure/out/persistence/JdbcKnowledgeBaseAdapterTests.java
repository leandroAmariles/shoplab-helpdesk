package com.shoplab.helpdesk.retrieval.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.retrieval.domain.model.IngestedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.ParsedChunk;
import com.shoplab.helpdesk.retrieval.domain.model.ParsedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.enums.DocumentFormat;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.exception.TechnicalException;
import com.shoplab.helpdesk.support.PostgresTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.test.StepVerifier;

/** The chunks of the ingest service are stored without vector, all or nothing (specs/LAB-20, FR-012 to FR-014). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class JdbcKnowledgeBaseAdapterTests {

    @Autowired
    private JdbcKnowledgeBaseAdapter adapter;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestDocuments() {
        jdbc.update("DELETE FROM retrieval.document WHERE source LIKE 'test/%'");
    }

    private static ParsedDocument document(String source, String title, DocumentFormat format, String... sections) {
        List<ParsedChunk> chunks = new java.util.ArrayList<>();
        for (int index = 0; index < sections.length; index++) {
            chunks.add(new ParsedChunk(index, "Texto " + index + " con acentos: año, niños.", sections[index]));
        }
        return new ParsedDocument(source, title, format, chunks);
    }

    private IngestedDocument store(ParsedDocument parsed) {
        return adapter.replaceDocument(parsed).block();
    }

    private int countChunks(UUID documentId) {
        return jdbc.queryForObject("SELECT count(*) FROM retrieval.chunk WHERE document_id = ?", Integer.class,
                documentId);
    }

    @Test
    void storesTheDocumentAndEachChunkWithItsTextPositionAndMetadata() {
        IngestedDocument stored = store(document("test/politica.md", "Politica", DocumentFormat.MARKDOWN,
                "Politica", "Politica > Plazos", "Politica > Costos"));

        assertThat(stored.source()).isEqualTo("test/politica.md");
        assertThat(stored.chunkCount()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT title || '|' || (metadata ->> 'format') FROM retrieval.document "
                + "WHERE id = ?", String.class, stored.documentId())).isEqualTo("Politica|markdown");
        List<String> rows = jdbc.queryForList("""
                SELECT chunk_index || '|' || content || '|' || (metadata ->> 'document') || '|'
                       || (metadata ->> 'section') || '|' || (metadata ->> 'position')
                FROM retrieval.chunk WHERE document_id = ? ORDER BY chunk_index
                """, String.class, stored.documentId());
        assertThat(rows).containsExactly(
                "0|Texto 0 con acentos: año, niños.|test/politica.md|Politica|0",
                "1|Texto 1 con acentos: año, niños.|test/politica.md|Politica > Plazos|1",
                "2|Texto 2 con acentos: año, niños.|test/politica.md|Politica > Costos|2");
    }

    @Test
    void theChunksWaitForTheirVector() {
        IngestedDocument stored = store(document("test/pending.pdf", "Pending", DocumentFormat.PDF, "", ""));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM retrieval.chunk
                WHERE document_id = ? AND embedding IS NULL AND embedding_model IS NULL
                """, Integer.class, stored.documentId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT metadata ->> 'format' FROM retrieval.document WHERE id = ?",
                String.class, stored.documentId())).isEqualTo("pdf");
    }

    @Test
    void ingestingTheSameSourceAgainKeepsTheDocumentAndReplacesItsChunks() {
        IngestedDocument first = store(document("test/again.md", "Before", DocumentFormat.MARKDOWN, "A", "B", "C"));

        IngestedDocument second = store(document("test/again.md", "After", DocumentFormat.MARKDOWN, "X"));

        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval.document WHERE source = 'test/again.md'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM retrieval.document WHERE id = ?", String.class,
                first.documentId())).isEqualTo("After");
        assertThat(jdbc.queryForList("SELECT metadata ->> 'section' FROM retrieval.chunk WHERE document_id = ?",
                String.class, first.documentId())).containsExactly("X");
        assertThat(jdbc.queryForObject("SELECT updated_at > created_at FROM retrieval.document WHERE id = ?",
                Boolean.class, first.documentId())).isTrue();
    }

    @Test
    void ingestingTheSameDocumentTwiceLeavesTheSameRows() {
        ParsedDocument parsed = document("test/twice.md", "Twice", DocumentFormat.MARKDOWN, "A", "B");

        IngestedDocument first = store(parsed);
        IngestedDocument second = store(parsed);

        assertThat(second).isEqualTo(first);
        assertThat(countChunks(first.documentId())).isEqualTo(2);
    }

    @Test
    void aFailureInTheMiddleLeavesTheStoredDocumentAsItWas() {
        IngestedDocument stored = store(document("test/atomic.md", "Original", DocumentFormat.MARKDOWN, "A", "B", "C"));
        ParsedDocument broken = new ParsedDocument("test/atomic.md", "Changed", DocumentFormat.PDF, List.of(
                new ParsedChunk(0, "uno", "A"), new ParsedChunk(0, "repetido", "A")));

        StepVerifier.create(adapter.replaceDocument(broken)).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(TechnicalException.class);
            assertThat(((HelpdeskException) error).getResponseCode()).isEqualTo(ResponseCodeEnum.DATABASE_UNAVAILABLE);
        }).verify();

        assertThat(jdbc.queryForObject("SELECT title || '|' || (metadata ->> 'format') FROM retrieval.document "
                + "WHERE id = ?", String.class, stored.documentId())).isEqualTo("Original|markdown");
        assertThat(jdbc.queryForList("SELECT content FROM retrieval.chunk WHERE document_id = ? ORDER BY chunk_index",
                String.class, stored.documentId())).hasSize(3).first().asString().startsWith("Texto 0");
    }

    @Test
    void aFailureOnTheFirstIngestionStoresNoDocument() {
        ParsedDocument broken = new ParsedDocument("test/none.md", "None", DocumentFormat.MARKDOWN, List.of(
                new ParsedChunk(0, "uno", "A"), new ParsedChunk(0, "repetido", "A")));

        StepVerifier.create(adapter.replaceDocument(broken)).expectError(TechnicalException.class).verify();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM retrieval.document WHERE source = 'test/none.md'",
                Integer.class)).isZero();
    }

    @Test
    void aBigDocumentIsStoredInOneGo() {
        String[] sections = new String[1200];
        java.util.Arrays.fill(sections, "Seccion");

        IngestedDocument stored = store(document("test/big.md", "Big", DocumentFormat.MARKDOWN, sections));

        assertThat(stored.chunkCount()).isEqualTo(1200);
        assertThat(countChunks(stored.documentId())).isEqualTo(1200);
    }
}
