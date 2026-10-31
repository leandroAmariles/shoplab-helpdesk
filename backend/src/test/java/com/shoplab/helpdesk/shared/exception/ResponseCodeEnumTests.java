package com.shoplab.helpdesk.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** The catalogue of error codes: unique, well formed, and with the statuses the retrieval ingestion relies on. */
class ResponseCodeEnumTests {

    @Test
    void everyCodeIsUniqueAndFollowsTheServiceFormat() {
        assertThat(Arrays.stream(ResponseCodeEnum.values()).map(ResponseCodeEnum::getCode).toList())
                .doesNotHaveDuplicates()
                .allMatch(code -> code.matches("SLHD_[A-Z]{4}_\\d{5}"));
    }

    @Test
    void theRetrievalIngestionCodesCarryTheirHttpStatus() {
        assertThat(ResponseCodeEnum.DOCUMENT_UNSUPPORTED_FORMAT.getCode()).isEqualTo("SLHD_RETR_00001");
        assertThat(ResponseCodeEnum.DOCUMENT_UNSUPPORTED_FORMAT.getHttpStatus())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(ResponseCodeEnum.DOCUMENT_TOO_LARGE.getCode()).isEqualTo("SLHD_RETR_00002");
        assertThat(ResponseCodeEnum.DOCUMENT_TOO_LARGE.getHttpStatus().value()).isEqualTo(413);
        assertThat(ResponseCodeEnum.DOCUMENT_INVALID.getCode()).isEqualTo("SLHD_RETR_00003");
        assertThat(ResponseCodeEnum.DOCUMENT_INVALID.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ResponseCodeEnum.INGEST_SERVICE_UNAVAILABLE.getCode()).isEqualTo("SLHD_RETR_00004");
        assertThat(ResponseCodeEnum.INGEST_SERVICE_UNAVAILABLE.getHttpStatus())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ResponseCodeEnum.INGEST_SERVICE_FAILED.getCode()).isEqualTo("SLHD_RETR_00005");
        assertThat(ResponseCodeEnum.INGEST_SERVICE_FAILED.getHttpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
