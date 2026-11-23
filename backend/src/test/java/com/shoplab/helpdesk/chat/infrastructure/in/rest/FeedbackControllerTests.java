package com.shoplab.helpdesk.chat.infrastructure.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.application.port.in.ListFeedbackQuery;
import com.shoplab.helpdesk.chat.application.port.in.ListFeedbackServicePort;
import com.shoplab.helpdesk.chat.application.port.in.PromoteFeedbackServicePort;
import com.shoplab.helpdesk.chat.application.port.in.SubmitFeedbackServicePort;
import com.shoplab.helpdesk.chat.domain.exception.ConversationMemoryException;
import com.shoplab.helpdesk.chat.domain.exception.FeedbackAlreadyPromotedException;
import com.shoplab.helpdesk.chat.domain.exception.FeedbackNotNegativeException;
import com.shoplab.helpdesk.chat.domain.exception.FeedbackTurnNotFoundException;
import com.shoplab.helpdesk.chat.domain.exception.InvalidGoldenCaseException;
import com.shoplab.helpdesk.chat.domain.model.FeedbackItem;
import com.shoplab.helpdesk.chat.domain.model.FeedbackListing;
import com.shoplab.helpdesk.chat.domain.model.FeedbackRecord;
import com.shoplab.helpdesk.chat.domain.model.enums.FeedbackRating;
import com.shoplab.helpdesk.chat.domain.model.enums.MemoryOperation;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.handler.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(FeedbackController.class)
@Import({RequestIdFilter.class, GlobalExceptionHandler.class})
class FeedbackControllerTests {

    private static final String TURN_ID = "2b6f1a04-9c44-4d30-9f51-8f2a10c4c7a9";
    private static final String CONV_ID = "9c3a2011-7e51-4a0e-88a4-1a1c80f7c111";
    private static final Instant NOW = Instant.parse("2026-11-23T15:10:00Z");

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private SubmitFeedbackServicePort submitFeedback;

    @MockitoBean
    private ListFeedbackServicePort listFeedback;

    @MockitoBean
    private PromoteFeedbackServicePort promoteFeedback;

    @Test
    void submitReturns200WithFeedbackPayload() {
        when(submitFeedback.submit(any())).thenReturn(Mono.just(
                new FeedbackRecord(TURN_ID, FeedbackRating.DOWN, "No indica el plazo", NOW, NOW, null, null)));

        webTestClient.put().uri("/api/chat/feedback/" + TURN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"rating\":\"DOWN\",\"comment\":\"No indica el plazo\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.turnId").isEqualTo(TURN_ID)
                .jsonPath("$.rating").isEqualTo("DOWN")
                .jsonPath("$.comment").isEqualTo("No indica el plazo");
    }

    @Test
    void submitMapsInvalidRatingNotFoundAlreadyPromotedAndUnavailableToContractStatuses() {
        webTestClient.put().uri("/api/chat/feedback/" + TURN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"rating\":\"UNKNOWN\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00011");

        when(submitFeedback.submit(any())).thenReturn(Mono.error(new FeedbackTurnNotFoundException()));
        webTestClient.put().uri("/api/chat/feedback/" + TURN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"rating\":\"UP\"}")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00012");

        when(submitFeedback.submit(any())).thenReturn(Mono.error(new FeedbackAlreadyPromotedException()));
        webTestClient.put().uri("/api/chat/feedback/" + TURN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"rating\":\"DOWN\"}")
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00013");

        when(submitFeedback.submit(any())).thenReturn(Mono.error(
                new ConversationMemoryException(MemoryOperation.WRITE, new IllegalStateException("db down"))));
        webTestClient.put().uri("/api/chat/feedback/" + TURN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"rating\":\"UP\"}")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00006");
    }

    @Test
    void listReturns200WithItemsAndSuggestedNextCaseId() {
        FeedbackItem item = new FeedbackItem(
                TURN_ID, CONV_ID, "¿Plazo?", "15 días.", List.of("returns-01-return-policy"),
                "4bf92f3577b34da6a3ce929d0e0e4736", FeedbackRating.DOWN, "Son 30", NOW, NOW, null, null, null);
        when(listFeedback.list(new ListFeedbackQuery(FeedbackRating.DOWN, false, 50)))
                .thenReturn(Mono.just(new FeedbackListing(1, 1, 0, "G121", List.of(item))));

        webTestClient.get().uri("/api/chat/feedback?rating=DOWN&promoted=false&limit=50")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(1)
                .jsonPath("$.pendingCount").isEqualTo(1)
                .jsonPath("$.promotedCount").isEqualTo(0)
                .jsonPath("$.suggestedNextCaseId").isEqualTo("G121")
                .jsonPath("$.items[0].turnId").isEqualTo(TURN_ID)
                .jsonPath("$.items[0].deliveredDocuments[0]").isEqualTo("returns-01-return-policy");
    }

    @Test
    void listRejectsInvalidRatingOrLimitWith400() {
        webTestClient.get().uri("/api/chat/feedback?rating=BAD")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00011");

        when(listFeedback.list(any())).thenReturn(Mono.error(
                new BusinessException(ResponseCodeEnum.INVALID_FEEDBACK)));
        webTestClient.get().uri("/api/chat/feedback?limit=999")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00011");
    }

    @Test
    void promoteReturns200OrMapsValidationAndConflictErrors() {
        String jsonl = "{\"id\": \"G121\", \"question\": \"¿Plazo de devolución?\", "
                + "\"reference_answer\": \"Tienes 30 días naturales desde la fecha de entrega.\", "
                + "\"expected_documents\": [\"returns-01-return-policy\"], \"category\": \"returns\"}";
        FeedbackItem promoted = new FeedbackItem(
                TURN_ID, CONV_ID, "¿Plazo?", "15 días.", List.of("returns-01-return-policy"),
                null, FeedbackRating.DOWN, "Son 30", NOW, NOW, "G121", jsonl, NOW);
        when(promoteFeedback.promote(any())).thenReturn(Mono.just(promoted));

        String requestBody = """
                {
                  "caseId": "G121",
                  "question": "¿Plazo de devolución?",
                  "referenceAnswer": "Tienes 30 días naturales desde la fecha de entrega.",
                  "expectedDocuments": ["returns-01-return-policy"],
                  "category": "returns"
                }
                """;

        webTestClient.post().uri("/api/chat/feedback/" + TURN_ID + "/promote")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.promotedCaseId").isEqualTo("G121")
                .jsonPath("$.promotedCaseJsonl").isEqualTo(jsonl);

        when(promoteFeedback.promote(any())).thenReturn(Mono.error(
                new InvalidGoldenCaseException("G-NOANSWER-DIGITS: no puede contener cifras")));
        webTestClient.post().uri("/api/chat/feedback/" + TURN_ID + "/promote")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SLHD_CHAT_00014")
                .jsonPath("$.detail").value(detail -> org.assertj.core.api.Assertions.assertThat((String) detail)
                        .contains("G-NOANSWER-DIGITS"));

        when(promoteFeedback.promote(any())).thenReturn(Mono.error(new FeedbackNotNegativeException()));
        webTestClient.post().uri("/api/chat/feedback/" + TURN_ID + "/promote")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isEqualTo(422)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SLHD_CHAT_00015");

        when(promoteFeedback.promote(any())).thenReturn(Mono.error(new FeedbackAlreadyPromotedException()));
        webTestClient.post().uri("/api/chat/feedback/" + TURN_ID + "/promote")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SLHD_CHAT_00013");
    }
}
