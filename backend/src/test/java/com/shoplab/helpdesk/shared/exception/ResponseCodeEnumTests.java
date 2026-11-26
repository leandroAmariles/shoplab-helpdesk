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
    void theEvalStoreUnavailableCodeIsServiceUnavailable() {
        assertThat(ResponseCodeEnum.EVAL_STORE_UNAVAILABLE.getCode()).isEqualTo("SLHD_EVAL_00005");
        assertThat(ResponseCodeEnum.EVAL_STORE_UNAVAILABLE.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ResponseCodeEnum.EVAL_STORE_UNAVAILABLE.getDefaultMessage()).isNotBlank();
    }

    @Test
    void anOversizedBodyKeepsTheCodeItHadBeforeTheDocumentsMoved() {
        assertThat(ResponseCodeEnum.DOCUMENT_TOO_LARGE.getCode()).isEqualTo("SLHD_RETR_00002");
        assertThat(ResponseCodeEnum.DOCUMENT_TOO_LARGE.getHttpStatus().value()).isEqualTo(413);
    }
}
