package com.shoplab.helpdesk.shared.exception;

import org.springframework.http.HttpStatus;

/** Single catalogue of error codes, format {@code <SERVICE>_XXXX_NNNNN} with service prefix {@code SLHD}. */
public enum ResponseCodeEnum {

    INVALID_CHAT_MESSAGE("SLHD_CHAT_00001", HttpStatus.BAD_REQUEST, "The message must not be blank or too long"),
    CHAT_MODEL_UNAVAILABLE("SLHD_CHAT_00002", HttpStatus.SERVICE_UNAVAILABLE, "The chat model is not available"),
    CHAT_STREAM_FAILED("SLHD_CHAT_00003", HttpStatus.INTERNAL_SERVER_ERROR,
            "The chat model failed while generating the answer"),
    DOCUMENT_UNSUPPORTED_FORMAT("SLHD_RETR_00001", HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "The document format is not supported: only PDF and Markdown are accepted"),
    DOCUMENT_TOO_LARGE("SLHD_RETR_00002", HttpStatus.CONTENT_TOO_LARGE, "The document is too large"),
    DOCUMENT_INVALID("SLHD_RETR_00003", HttpStatus.UNPROCESSABLE_ENTITY,
            "The document is empty, has no extractable text, is corrupt or cannot be chunked"),
    INGEST_SERVICE_UNAVAILABLE("SLHD_RETR_00004", HttpStatus.SERVICE_UNAVAILABLE,
            "The document parsing service is not available"),
    INGEST_SERVICE_FAILED("SLHD_RETR_00005", HttpStatus.BAD_GATEWAY,
            "The document parsing service failed"),
    DATABASE_UNAVAILABLE("SLHD_DBAS_00001", HttpStatus.SERVICE_UNAVAILABLE, "The database is not available"),
    DATABASE_MIGRATION_FAILED("SLHD_DBAS_00002", HttpStatus.INTERNAL_SERVER_ERROR,
            "A module schema migration failed"),
    UNEXPECTED_ERROR("SLHD_GENE_00001", HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error"),
    INVALID_REQUEST_BODY("SLHD_GENE_00002", HttpStatus.BAD_REQUEST, "The request body could not be read");

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ResponseCodeEnum(String code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
