package com.shoplab.helpdesk.shared.infrastructure.in.rest.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.exception.ConversationMemoryException;
import com.shoplab.helpdesk.chat.domain.model.ConversationId;
import com.shoplab.helpdesk.chat.domain.model.enums.MemoryOperation;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.exception.TechnicalException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebInputException;

@WebFluxTest(controllers = GlobalExceptionHandlerTests.ProbeController.class)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTests.ProbeController.class})
class GlobalExceptionHandlerTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void businessExceptionUsesTheStatusAndCodeOfItsResponseCode() {
        webTestClient.get().uri("/probe/business").exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SLHD_CHAT_00001")
                .jsonPath("$.message").isNotEmpty();
    }

    @Test
    void technicalExceptionWithModelUnavailableCodeIsServiceUnavailable() {
        webTestClient.get().uri("/probe/technical").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00002");
    }

    @Test
    void anInvalidConversationIdIsABadRequestWithoutTheValue() {
        webTestClient.get().uri("/probe/conversation-id").exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).value(text -> assertThat(text)
                        .contains("SLHD_CHAT_00005").doesNotContain("not-a-uuid"));
    }

    @Test
    void aMemoryFailureIsServiceUnavailableWithoutLeakingItsCause() {
        webTestClient.get().uri("/probe/memory").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody(String.class).value(text -> assertThat(text)
                        .contains("SLHD_CHAT_00006").doesNotContain("secret memory detail"));
    }

    @Test
    void unexpectedExceptionIsInternalErrorWithoutLeakingItsMessage() {
        webTestClient.get().uri("/probe/unexpected").exchange()
                .expectStatus().is5xxServerError()
                .expectBody(String.class).value(text -> assertThat(text)
                        .contains("SLHD_GENE_00001").doesNotContain("secret internal detail"));
    }

    @Test
    void unreadableBodyIsBadRequestWithGenericCode() {
        webTestClient.post().uri("/probe/body").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"message\":").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_GENE_00002");
    }

    @Test
    void errorIsJsonEvenWhenTheClientAsksForEventStream() {
        webTestClient.get().uri("/probe/stream-technical").accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_CHAT_00002");
    }

    @Test
    void unreadableBodyIsJsonEvenWhenTheClientAsksForEventStream() {
        webTestClient.post().uri("/probe/stream-body").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM).bodyValue("{\"message\":").exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_GENE_00002");
    }

    @Test
    void aBodyOverTheLimitIsTooLargeWithTheDocumentCode() {
        webTestClient.get().uri("/probe/limit").exchange()
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_RETR_00002");
    }

    @Test
    void anInputExceptionCausedByTheLimitIsTooLargeToo() {
        webTestClient.get().uri("/probe/wrapped-limit").exchange()
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.code").isEqualTo("SLHD_RETR_00002");
    }

    /** Test-only routes that throw each kind of exception; the {@code stream-*} ones only produce event streams. */
    @RestController
    static class ProbeController {

        @GetMapping("/probe/business")
        String business() {
            throw new BusinessException(ResponseCodeEnum.INVALID_CHAT_MESSAGE);
        }

        @GetMapping("/probe/technical")
        String technical() {
            throw new TechnicalException(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE, new IllegalStateException("down"));
        }

        @GetMapping(path = "/probe/stream-technical", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        String streamTechnical() {
            throw new TechnicalException(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE, new IllegalStateException("down"));
        }

        @GetMapping("/probe/conversation-id")
        String conversationId() {
            return ConversationId.optional("not-a-uuid").orElseThrow().value();
        }

        @GetMapping("/probe/memory")
        String memory() {
            throw new ConversationMemoryException(MemoryOperation.READ,
                    new IllegalStateException("secret memory detail"));
        }

        @GetMapping("/probe/unexpected")
        String unexpected() {
            throw new IllegalStateException("secret internal detail");
        }

        @GetMapping("/probe/limit")
        String limit() {
            throw new DataBufferLimitException("Exceeded limit on max bytes to buffer : 64");
        }

        @GetMapping("/probe/wrapped-limit")
        String wrappedLimit() {
            throw new ServerWebInputException("Failed to read multipart", null,
                    new DataBufferLimitException("Too much data"));
        }

        @PostMapping("/probe/body")
        String body(@RequestBody ProbeBody body) {
            return body.message();
        }

        @PostMapping(path = "/probe/stream-body", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        String streamBody(@RequestBody ProbeBody body) {
            return body.message();
        }
    }

    record ProbeBody(String message) {
    }
}
