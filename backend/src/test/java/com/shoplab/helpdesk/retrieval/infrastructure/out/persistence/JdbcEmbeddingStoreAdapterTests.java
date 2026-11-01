package com.shoplab.helpdesk.retrieval.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.retrieval.domain.model.ChunkEmbedding;
import com.shoplab.helpdesk.retrieval.domain.model.IngestedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.ParsedChunk;
import com.shoplab.helpdesk.retrieval.domain.model.ParsedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.PendingDocument;
import com.shoplab.helpdesk.retrieval.domain.model.SearchResult;
import com.shoplab.helpdesk.retrieval.domain.model.enums.DocumentFormat;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IndexScope;
import com.shoplab.helpdesk.retrieval.support.DeterministicEmbeddingPort;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.support.PostgresTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.test.StepVerifier;

/**
 * Vectors are stored per document, all or nothing and only while the document keeps the version that was read; the
 * search only sees vectors of the asked model (specs/LAB-21, FR-004 to FR-010 and FR-013).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class JdbcEmbeddingStoreAdapterTests {

    private static final String MODEL_A = "test-model-a";
    private static final String MODEL_B = "test-model-b";

    @Autowired
    private JdbcEmbeddingStoreAdapter store;

    @Autowired
    private JdbcKnowledgeBaseAdapter knowledgeBase;

    @Autowired
    private JdbcTemplate jdbc;

    /** Deletes and vacuums: an HNSW index keeps the entries of deleted rows until a vacuum (see LAB-19 tests). */
    @AfterEach
    void removeTestDocuments() {
        jdbc.update("DELETE FROM retrieval.document WHERE source LIKE 'test/emb-%'");
        jdbc.execute("VACUUM retrieval.chunk");
    }

    private IngestedDocument ingest(String source, String... texts) {
        List<ParsedChunk> chunks = new ArrayList<>();
        for (int index = 0; index < texts.length; index++) {
            chunks.add(new ParsedChunk(index, texts[index], "Seccion " + index));
        }
        return knowledgeBase.replaceDocument(new ParsedDocument(source, source, DocumentFormat.MARKDOWN, chunks))
                .block();
    }

    private PendingDocument read(String source) {
        return store.findDocuments(IndexScope.ALL, MODEL_A).filter(document -> document.source().equals(source))
                .blockFirst();
    }

    private List<String> pendingSources(IndexScope scope, String model) {
        return store.findDocuments(scope, model).map(PendingDocument::source)
                .filter(source -> source.startsWith("test/emb-")).collectList().block();
    }

    private static List<ChunkEmbedding> vectorsOf(PendingDocument document) {
        return document.chunks().stream()
                .map(chunk -> new ChunkEmbedding(chunk.chunkId(), DeterministicEmbeddingPort.vectorFor(chunk.text())))
                .toList();
    }

    private String state(UUID documentId) {
        return jdbc.queryForObject("""
                SELECT index_status || '|' || coalesce(index_error, '-') || '|' || (indexed_at IS NOT NULL)
                FROM retrieval.document WHERE id = ?
                """, String.class, documentId);
    }

    private int chunksWithVector(UUID documentId) {
        return jdbc.queryForObject("SELECT count(*) FROM retrieval.chunk WHERE document_id = ? "
                + "AND embedding IS NOT NULL AND embedding_model IS NOT NULL", Integer.class, documentId);
    }

    private void index(String source, String model) {
        PendingDocument document = read(source);
        assertThat(store.saveEmbeddings(document, vectorsOf(document), model).block()).isTrue();
    }

    @Test
    void findsOnlyThePendingDocumentsWithTheirChunksInOrder() {
        ingest("test/emb-pending.md", "primero", "segundo", "tercero");
        ingest("test/emb-done.md", "listo");
        index("test/emb-done.md", MODEL_A);

        List<PendingDocument> found = store.findDocuments(IndexScope.PENDING, MODEL_A)
                .filter(document -> document.source().startsWith("test/emb-")).collectList().block();

        assertThat(found).singleElement().satisfies(document -> {
            assertThat(document.source()).isEqualTo("test/emb-pending.md");
            assertThat(document.chunks()).extracting("text").containsExactly("primero", "segundo", "tercero");
            assertThat(document.chunks()).extracting("position").containsExactly(0, 1, 2);
            assertThat(document.version()).isNotNull();
        });
    }

    @Test
    void retryingAlsoGoesThroughTheFailedDocuments() {
        ingest("test/emb-failed.md", "uno");
        ingest("test/emb-new.md", "dos");
        store.markFailed(read("test/emb-failed.md"), "model down").block();

        assertThat(pendingSources(IndexScope.PENDING, MODEL_A)).containsExactly("test/emb-new.md");
        assertThat(pendingSources(IndexScope.RETRY, MODEL_A)).containsExactly("test/emb-failed.md",
                "test/emb-new.md");
    }

    @Test
    void anotherModelScopeFindsDocumentsWithVectorsOfOtherModelsButNotThoseAlreadyOnTheModel() {
        ingest("test/emb-a.md", "uno");
        ingest("test/emb-b.md", "dos");
        index("test/emb-a.md", MODEL_A);
        index("test/emb-b.md", MODEL_B);

        assertThat(pendingSources(IndexScope.OTHER_MODEL, MODEL_A)).containsExactly("test/emb-b.md");
        assertThat(pendingSources(IndexScope.OTHER_MODEL, MODEL_B)).containsExactly("test/emb-a.md");
        assertThat(pendingSources(IndexScope.ALL, MODEL_A)).containsExactly("test/emb-a.md", "test/emb-b.md");
    }

    @Test
    void storesTheVectorsOfAllTheChunksWithTheModelAndMarksTheDocumentIndexed() {
        IngestedDocument stored = ingest("test/emb-save.md", "uno", "dos");
        PendingDocument document = read("test/emb-save.md");

        StepVerifier.create(store.saveEmbeddings(document, vectorsOf(document), MODEL_A))
                .expectNext(true).verifyComplete();

        assertThat(chunksWithVector(stored.documentId())).isEqualTo(2);
        assertThat(state(stored.documentId())).isEqualTo("INDEXED|-|true");
        assertThat(jdbc.queryForObject("""
                SELECT count(DISTINCT embedding_model) || '|' || min(embedding_model) FROM retrieval.chunk
                WHERE document_id = ?
                """, String.class, stored.documentId())).isEqualTo("1|" + MODEL_A);
        assertThat(jdbc.queryForObject("""
                SELECT embedding = ?::public.vector FROM retrieval.chunk WHERE document_id = ? AND chunk_index = 0
                """, Boolean.class, VectorLiteral.of(DeterministicEmbeddingPort.vectorFor("uno")),
                stored.documentId())).isTrue();
    }

    @Test
    void theTextThePositionAndTheMetadataOfTheChunksStayAsTheyWere() {
        IngestedDocument stored = ingest("test/emb-intact.md", "uno", "dos");
        String before = chunkContents(stored.documentId());
        PendingDocument document = read("test/emb-intact.md");

        store.saveEmbeddings(document, vectorsOf(document), MODEL_A).block();

        assertThat(chunkContents(stored.documentId())).isEqualTo(before);
    }

    private String chunkContents(UUID documentId) {
        return jdbc.queryForObject("""
                SELECT string_agg(chunk_index || content || metadata::text, ';' ORDER BY chunk_index)
                FROM retrieval.chunk WHERE document_id = ?
                """, String.class, documentId);
    }

    @Test
    void doesNothingWhenTheDocumentWasIngestedAgainAfterItWasRead() {
        ingest("test/emb-stale.md", "version uno");
        PendingDocument readEarlier = read("test/emb-stale.md");
        IngestedDocument again = ingest("test/emb-stale.md", "version dos", "version tres");

        StepVerifier.create(store.saveEmbeddings(readEarlier, vectorsOf(readEarlier), MODEL_A))
                .expectNext(false).verifyComplete();

        assertThat(chunksWithVector(again.documentId())).isZero();
        assertThat(state(again.documentId())).isEqualTo("PENDING|-|false");
    }

    @Test
    void aVectorOfTheWrongSizeStoresNothing() {
        IngestedDocument stored = ingest("test/emb-size.md", "uno", "dos");
        PendingDocument document = read("test/emb-size.md");
        List<ChunkEmbedding> vectors = List.of(
                new ChunkEmbedding(document.chunks().get(0).chunkId(), new float[1024]),
                new ChunkEmbedding(document.chunks().get(1).chunkId(), new float[768]));

        StepVerifier.create(store.saveEmbeddings(document, vectors, MODEL_A))
                .expectErrorSatisfies(error -> assertThat(((HelpdeskException) error).getResponseCode())
                        .isEqualTo(ResponseCodeEnum.EMBEDDING_DIMENSION_MISMATCH))
                .verify();

        assertThat(chunksWithVector(stored.documentId())).isZero();
        assertThat(state(stored.documentId())).isEqualTo("PENDING|-|false");
    }

    @Test
    void aChunkThatNoLongerExistsRollsTheWholeDocumentBack() {
        IngestedDocument stored = ingest("test/emb-gone.md", "uno", "dos");
        PendingDocument document = read("test/emb-gone.md");
        List<ChunkEmbedding> vectors = new ArrayList<>(vectorsOf(document));
        vectors.add(new ChunkEmbedding(UUID.randomUUID(), DeterministicEmbeddingPort.vectorFor("fantasma")));

        StepVerifier.create(store.saveEmbeddings(document, vectors, MODEL_A)).expectNext(false).verifyComplete();

        assertThat(chunksWithVector(stored.documentId())).isZero();
        assertThat(state(stored.documentId())).isEqualTo("PENDING|-|false");
    }

    @Test
    void otherDocumentsAreNotTouched() {
        ingest("test/emb-target.md", "uno");
        IngestedDocument bystander = ingest("test/emb-bystander.md", "otro");

        index("test/emb-target.md", MODEL_A);

        assertThat(chunksWithVector(bystander.documentId())).isZero();
        assertThat(state(bystander.documentId())).isEqualTo("PENDING|-|false");
    }

    @Test
    void reindexingReplacesTheVectorsAndTheModelOfTheWholeDocumentAtOnce() {
        IngestedDocument stored = ingest("test/emb-switch.md", "uno", "dos");
        index("test/emb-switch.md", MODEL_A);
        PendingDocument document = read("test/emb-switch.md");

        assertThat(store.saveEmbeddings(document, vectorsOf(document), MODEL_B).block()).isTrue();

        assertThat(jdbc.queryForObject("SELECT string_agg(DISTINCT embedding_model, ',') FROM retrieval.chunk "
                + "WHERE document_id = ?", String.class, stored.documentId())).isEqualTo(MODEL_B);
        assertThat(state(stored.documentId())).isEqualTo("INDEXED|-|true");
    }

    @Test
    void markingAFailureKeepsTheVectorsAndTheReason() {
        IngestedDocument stored = ingest("test/emb-error.md", "uno");
        index("test/emb-error.md", MODEL_A);
        PendingDocument document = read("test/emb-error.md");

        StepVerifier.create(store.markFailed(document, "The embedding model is not available")).verifyComplete();

        assertThat(state(stored.documentId())).isEqualTo("ERROR|The embedding model is not available|false");
        assertThat(chunksWithVector(stored.documentId())).isEqualTo(1);
    }

    @Test
    void aFailureOfAnOldVersionDoesNotMarkADocumentIngestedAgain() {
        ingest("test/emb-late.md", "uno");
        PendingDocument readEarlier = read("test/emb-late.md");
        IngestedDocument again = ingest("test/emb-late.md", "dos");

        store.markFailed(readEarlier, "too late").block();

        assertThat(state(again.documentId())).isEqualTo("PENDING|-|false");
    }

    @Test
    void aFailedDocumentBecomesIndexedWhenTheRetryWorks() {
        IngestedDocument stored = ingest("test/emb-retry.md", "uno");
        store.markFailed(read("test/emb-retry.md"), "down").block();
        PendingDocument document = read("test/emb-retry.md");

        assertThat(store.saveEmbeddings(document, vectorsOf(document), MODEL_A).block()).isTrue();

        assertThat(state(stored.documentId())).isEqualTo("INDEXED|-|true");
    }

    @Test
    void searchesOnlyAmongChunksWithAVectorOfTheAskedModelNearestFirst() {
        ingest("test/emb-search-a.md", "plazo de devolucion", "costo de envio", "horario de atencion");
        index("test/emb-search-a.md", MODEL_A);
        ingest("test/emb-search-b.md", "plazo de devolucion");
        index("test/emb-search-b.md", MODEL_B);
        ingest("test/emb-search-pending.md", "plazo de devolucion");

        List<SearchResult> results = store.search(DeterministicEmbeddingPort.vectorFor("plazo de devolucion"),
                MODEL_A, 10).block();

        assertThat(results).hasSize(3);
        assertThat(results.get(0).text()).isEqualTo("plazo de devolucion");
        assertThat(results.get(0).source()).isEqualTo("test/emb-search-a.md");
        assertThat(results.get(0).section()).isEqualTo("Seccion 0");
        assertThat(results.get(0).position()).isZero();
        assertThat(results.get(0).score()).isGreaterThan(0.99);
        assertThat(results).extracting(SearchResult::source).containsOnly("test/emb-search-a.md");
        assertThat(results).extracting(SearchResult::score).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void theSameQueryOnAnotherModelFindsTheChunksOfThatModelOnly() {
        ingest("test/emb-model-a.md", "plazo");
        index("test/emb-model-a.md", MODEL_A);
        ingest("test/emb-model-b.md", "plazo");
        index("test/emb-model-b.md", MODEL_B);

        List<SearchResult> results = store.search(DeterministicEmbeddingPort.vectorFor("plazo"), MODEL_B, 5)
                .block();

        assertThat(results).extracting(SearchResult::source).containsExactly("test/emb-model-b.md");
    }

    @Test
    void respectsTheLimitAndAnswersEmptyWhenThereIsNothingToCompare() {
        ingest("test/emb-limit.md", "uno", "dos", "tres");
        index("test/emb-limit.md", MODEL_A);

        assertThat(store.search(DeterministicEmbeddingPort.vectorFor("uno"), MODEL_A, 2).block()).hasSize(2);
        assertThat(store.search(DeterministicEmbeddingPort.vectorFor("uno"), "test-model-nobody", 5).block())
                .isEmpty();
    }

    @Test
    void knowsWhenThereAreVectorsOfOtherModels() {
        ingest("test/emb-other.md", "uno");
        index("test/emb-other.md", MODEL_B);

        assertThat(store.hasChunksFromOtherModels(MODEL_A).block()).isTrue();
        jdbc.update("DELETE FROM retrieval.document WHERE source = 'test/emb-other.md'");
        assertThat(store.hasChunksFromOtherModels(MODEL_B).block()).isFalse();
    }
}
