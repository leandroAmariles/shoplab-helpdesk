package com.shoplab.helpdesk.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The catalogue of error codes: unique, well formed, and with the statuses their consumers rely on. */
class ResponseCodeEnumTests {

    @Test
    void everyCodeIsUniqueAndFollowsTheServiceFormat() {
        assertThat(Arrays.stream(ResponseCodeEnum.values()).map(ResponseCodeEnum::getCode).toList())
                .doesNotHaveDuplicates()
                .allMatch(code -> code.matches("SLHD_[A-Z]{4}_\\d{5}"));
    }

    @Test
    void theContextUnavailableCodeIsAServiceUnavailableOfTheChatModule() {
        assertThat(ResponseCodeEnum.CHAT_CONTEXT_UNAVAILABLE.getCode()).isEqualTo("SLHD_CHAT_00004");
        assertThat(ResponseCodeEnum.CHAT_CONTEXT_UNAVAILABLE.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ResponseCodeEnum.CHAT_CONTEXT_UNAVAILABLE.getDefaultMessage())
                .isEqualTo("The knowledge base is not available");
    }

    @Test
    void theConversationMemoryCodesBelongToTheChatModule() {
        assertThat(ResponseCodeEnum.INVALID_CONVERSATION_ID.getCode()).isEqualTo("SLHD_CHAT_00005");
        assertThat(ResponseCodeEnum.INVALID_CONVERSATION_ID.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ResponseCodeEnum.CHAT_MEMORY_UNAVAILABLE.getCode()).isEqualTo("SLHD_CHAT_00006");
        assertThat(ResponseCodeEnum.CHAT_MEMORY_UNAVAILABLE.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void theBudgetCodesAreTooManyRequestsAndDifferentFromModelUnavailable() {
        assertThat(ResponseCodeEnum.CHAT_BUDGET_EXCEEDED.getCode()).isEqualTo("SLHD_CHAT_00007");
        assertThat(ResponseCodeEnum.CHAT_BUDGET_EXCEEDED.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ResponseCodeEnum.CHAT_BUDGET_EXCEEDED.getCode())
                .isNotEqualTo(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE.getCode());
        assertThat(ResponseCodeEnum.CHAT_BUDGET_EXCEEDED.getDefaultMessage()).doesNotContainIgnoringCase("ollama");
    }

    @Test
    void anOversizedBodyKeepsTheCodeItHadBeforeTheDocumentsMoved() {
        assertThat(ResponseCodeEnum.DOCUMENT_TOO_LARGE.getCode()).isEqualTo("SLHD_RETR_00002");
        assertThat(ResponseCodeEnum.DOCUMENT_TOO_LARGE.getHttpStatus().value()).isEqualTo(413);
    }
}
