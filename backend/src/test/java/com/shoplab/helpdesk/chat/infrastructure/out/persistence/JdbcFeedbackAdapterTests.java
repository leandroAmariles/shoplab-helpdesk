package com.shoplab.helpdesk.chat.infrastructure.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.application.port.in.ListFeedbackQuery;
import com.shoplab.helpdesk.chat.domain.exception.ConversationMemoryException;
import com.shoplab.helpdesk.chat.domain.exception.FeedbackAlreadyPromotedException;
import com.shoplab.helpdesk.chat.domain.exception.FeedbackNotNegativeException;
import com.shoplab.helpdesk.chat.domain.exception.FeedbackTurnNotFoundException;
import com.shoplab.helpdesk.chat.domain.model.ConversationId;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.FeedbackItem;
import com.shoplab.helpdesk.chat.domain.model.FeedbackListing;
import com.shoplab.helpdesk.chat.domain.model.FeedbackRecord;
import com.shoplab.helpdesk.chat.domain.model.SubmitFeedbackCommand;
import com.shoplab.helpdesk.chat.domain.model.enums.FeedbackRating;
import com.shoplab.helpdesk.chat.domain.model.enums.MemoryOperation;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.support.PostgresTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@PostgresTest
class JdbcFeedbackAdapterTests {

    @Autowired
    private JdbcFeedbackAdapter feedbackAdapter;

    @Autowired
    private JdbcConversationMemoryAdapter memoryAdapter;

    @Autowired
    private JdbcTemplate jdbc;

    private final ConversationId conversationId = new ConversationId(UUID.randomUUID().toString());

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM chat.conversation_turn WHERE conversation_id = ?",
                UUID.fromString(conversationId.value()));
    }

    private String saveTurn(String question, String answer, List<String> docs, String traceId) {
        return memoryAdapter.saveTurn(
                conversationId, new ConversationTurn(question, answer, docs, traceId)).block();
    }

    @Test
    void upsertsFeedbackOnExistingTurnKeepingASingleRowWhenUpdated() {
        String turnId = saveTurn(
                "¿Cuántos días tengo para devolver?",
                "Tienes 15 días.",
                List.of("returns-01-return-policy"),
                "4bf92f3577b34da6a3ce929d0e0e4736");

        FeedbackRecord created = feedbackAdapter.upsert(
                new SubmitFeedbackCommand(turnId, FeedbackRating.UP, null)).block();
        assertThat(created).isNotNull();
        assertThat(created.turnId()).isEqualTo(turnId);
        assertThat(created.rating()).isEqualTo(FeedbackRating.UP);
        assertThat(created.comment()).isNull();
        assertThat(created.promotedCaseId()).isNull();

        FeedbackRecord updated = feedbackAdapter.upsert(
                new SubmitFeedbackCommand(turnId, FeedbackRating.DOWN, "La política dice 30 días.")).block();
        assertThat(updated).isNotNull();
        assertThat(updated.turnId()).isEqualTo(turnId);
        assertThat(updated.rating()).isEqualTo(FeedbackRating.DOWN);
        assertThat(updated.comment()).isEqualTo("La política dice 30 días.");
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(updated.updatedAt()).isAfterOrEqualTo(created.createdAt());

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM chat.feedback f JOIN chat.conversation_turn t ON t.id = f.turn_ref "
                        + "WHERE t.turn_id = ?",
                Integer.class, UUID.fromString(turnId));
        assertThat(count).isEqualTo(1);
    }

    @Test
    void rejectsUpsertWhenTurnDoesNotExist() {
        String missingTurnId = UUID.randomUUID().toString();

        StepVerifier.create(feedbackAdapter.upsert(
                        new SubmitFeedbackCommand(missingTurnId, FeedbackRating.DOWN, null)))
                .expectError(FeedbackTurnNotFoundException.class)
                .verify();
    }

    @Test
    void listsNegativeFeedbackNewestFirstAndComputesCountsAndSuggestedNextCaseId() {
        String firstTurn = saveTurn(
                "Pregunta 1", "Respuesta 1", List.of("returns-01-return-policy"),
                "4bf92f3577b34da6a3ce929d0e0e4736");
        String secondTurn = saveTurn(
                "Pregunta 2", "Respuesta 2", List.of("shipping-01-delivery-times"), null);
        String upTurn = saveTurn("Pregunta 3", "Respuesta 3", List.of(), null);

        feedbackAdapter.upsert(new SubmitFeedbackCommand(firstTurn, FeedbackRating.DOWN, "Comentario 1")).block();
        feedbackAdapter.upsert(new SubmitFeedbackCommand(secondTurn, FeedbackRating.DOWN, null)).block();
        feedbackAdapter.upsert(new SubmitFeedbackCommand(upTurn, FeedbackRating.UP, "Bien")).block();

        FeedbackListing initialListing = feedbackAdapter.list(
                new ListFeedbackQuery(FeedbackRating.DOWN, null, 100), 120).block();
        assertThat(initialListing).isNotNull();
        assertThat(initialListing.total()).isEqualTo(2);
        assertThat(initialListing.pendingCount()).isEqualTo(2);
        assertThat(initialListing.promotedCount()).isEqualTo(0);
        assertThat(initialListing.suggestedNextCaseId()).isEqualTo("G121");
        assertThat(initialListing.items()).extracting(FeedbackItem::turnId)
                .containsExactly(secondTurn, firstTurn);
        assertThat(initialListing.items().get(1).deliveredDocuments())
                .containsExactly("returns-01-return-policy");
        assertThat(initialListing.items().get(1).traceId())
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");

        String jsonl = "{\"id\": \"G121\", \"question\": \"Pregunta 1\", \"reference_answer\": "
                + "\"Respuesta de referencia válida con más de veinte caracteres.\", "
                + "\"expected_documents\": [\"returns-01-return-policy\"], \"category\": \"returns\"}";
        FeedbackItem promoted = feedbackAdapter.promote(firstTurn, "G121", jsonl).block();
        assertThat(promoted).isNotNull();
        assertThat(promoted.promotedCaseId()).isEqualTo("G121");
        assertThat(promoted.promotedCaseJsonl()).isEqualTo(jsonl);
        assertThat(promoted.promotedAt()).isNotNull();

        FeedbackListing pendingOnly = feedbackAdapter.list(
                new ListFeedbackQuery(FeedbackRating.DOWN, false, 100), 120).block();
        assertThat(pendingOnly).isNotNull();
        assertThat(pendingOnly.total()).isEqualTo(1);
        assertThat(pendingOnly.pendingCount()).isEqualTo(1);
        assertThat(pendingOnly.promotedCount()).isEqualTo(1);
        assertThat(pendingOnly.suggestedNextCaseId()).isEqualTo("G122");
        assertThat(pendingOnly.items()).extracting(FeedbackItem::turnId).containsExactly(secondTurn);

        FeedbackListing promotedOnly = feedbackAdapter.list(
                new ListFeedbackQuery(FeedbackRating.DOWN, true, 100), 120).block();
        assertThat(promotedOnly).isNotNull();
        assertThat(promotedOnly.total()).isEqualTo(1);
        assertThat(promotedOnly.items()).extracting(FeedbackItem::turnId).containsExactly(firstTurn);
    }

    @Test
    void enforcesPromotionInvariantsForNotFoundPositiveAlreadyPromotedAndDuplicateCaseId() {
        String downTurn = saveTurn("Pregunta 1", "Respuesta 1", List.of(), null);
        String secondDownTurn = saveTurn("Pregunta 2", "Respuesta 2", List.of(), null);
        String upTurn = saveTurn("Pregunta 3", "Respuesta 3", List.of(), null);

        StepVerifier.create(feedbackAdapter.promote(downTurn, "G121", "{\"id\":\"G121\"}"))
                .expectError(FeedbackTurnNotFoundException.class)
                .verify();

        feedbackAdapter.upsert(new SubmitFeedbackCommand(upTurn, FeedbackRating.UP, null)).block();
        StepVerifier.create(feedbackAdapter.promote(upTurn, "G121", "{\"id\":\"G121\"}"))
                .expectError(FeedbackNotNegativeException.class)
                .verify();

        feedbackAdapter.upsert(new SubmitFeedbackCommand(downTurn, FeedbackRating.DOWN, "Mal")).block();
        feedbackAdapter.upsert(new SubmitFeedbackCommand(secondDownTurn, FeedbackRating.DOWN, "Mal 2")).block();
        feedbackAdapter.promote(downTurn, "G121", "{\"id\":\"G121\"}").block();

        StepVerifier.create(feedbackAdapter.promote(downTurn, "G122", "{\"id\":\"G122\"}"))
                .expectError(FeedbackAlreadyPromotedException.class)
                .verify();
        StepVerifier.create(feedbackAdapter.promote(secondDownTurn, "G121", "{\"id\":\"G121\"}"))
                .expectError(FeedbackAlreadyPromotedException.class)
                .verify();
        StepVerifier.create(feedbackAdapter.upsert(
                        new SubmitFeedbackCommand(downTurn, FeedbackRating.UP, "Cambio")))
                .expectError(FeedbackAlreadyPromotedException.class)
                .verify();
    }

    @Test
    void runsOffTheEventLoopAndTranslatesDatabaseFailuresToConversationMemoryException() {
        String turnId = saveTurn("Pregunta", "Respuesta", List.of(), null);

        StepVerifier.create(feedbackAdapter.upsert(new SubmitFeedbackCommand(turnId, FeedbackRating.DOWN, "Motivo"))
                        .subscribeOn(Schedulers.parallel()))
                .expectNextMatches(record -> record.rating() == FeedbackRating.DOWN)
                .verifyComplete();

        DriverManagerDataSource brokenDataSource = new DriverManagerDataSource(
                "jdbc:postgresql://localhost:1/none", "nobody", "nothing");
        JdbcFeedbackAdapter broken = new JdbcFeedbackAdapter(
                new JdbcTemplate(brokenDataSource),
                new TransactionTemplate(new DataSourceTransactionManager(brokenDataSource)));

        StepVerifier.create(broken.upsert(new SubmitFeedbackCommand(turnId, FeedbackRating.UP, null)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ConversationMemoryException.class);
                    assertThat(((ConversationMemoryException) error).getOperation())
                            .isEqualTo(MemoryOperation.WRITE);
                    assertThat(((ConversationMemoryException) error).getResponseCode())
                            .isEqualTo(ResponseCodeEnum.CHAT_MEMORY_UNAVAILABLE);
                })
                .verify();

        StepVerifier.create(broken.list(new ListFeedbackQuery(FeedbackRating.DOWN, null, 10), 120))
                .expectErrorSatisfies(error -> assertThat(((ConversationMemoryException) error).getOperation())
                        .isEqualTo(MemoryOperation.READ))
                .verify();
    }
}
