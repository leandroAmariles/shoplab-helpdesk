package com.shoplab.helpdesk.shared.exception;

import org.springframework.http.HttpStatus;

/** Single catalogue of error codes, format {@code <SERVICE>_XXXX_NNNNN} with service prefix {@code SLHD}. */
public enum ResponseCodeEnum {

    // The documents moved to retrieval-service (LAB-129); the code stays for any request body over the limit,
    // which the global handler has always answered with it.
    DOCUMENT_TOO_LARGE("SLHD_RETR_00002", HttpStatus.CONTENT_TOO_LARGE, "The document is too large"),
    EVAL_PERSISTENCE_DISABLED("SLHD_EVAL_00001", HttpStatus.FORBIDDEN,
            "Saving evaluation runs is not enabled or the token is wrong"),
    EVAL_RUN_INVALID("SLHD_EVAL_00002", HttpStatus.UNPROCESSABLE_CONTENT, "The evaluation run is not valid"),
    EVAL_RUN_ALREADY_EXISTS("SLHD_EVAL_00003", HttpStatus.CONFLICT, "An evaluation run with that id already exists"),
    EVAL_RUN_NOT_FOUND("SLHD_EVAL_00004", HttpStatus.NOT_FOUND, "There is no evaluation run with that id"),
    EVAL_STORE_UNAVAILABLE("SLHD_EVAL_00005", HttpStatus.SERVICE_UNAVAILABLE,
            "The evaluation history is not available"),
    EVAL_RUNS_NOT_COMPARABLE("SLHD_EVAL_00006", HttpStatus.UNPROCESSABLE_CONTENT,
            "The evaluation runs are not of the same kind"),
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
