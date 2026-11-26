package com.shoplab.helpdesk.shared.exception;

import org.springframework.http.HttpStatus;

/** Single catalogue of error codes, format {@code <SERVICE>_XXXX_NNNNN} with service prefix {@code SLHD}. */
public enum ResponseCodeEnum {

    INVALID_CHAT_MESSAGE("SLHD_CHAT_00001", HttpStatus.BAD_REQUEST, "The message must not be blank or too long"),
    CHAT_MODEL_UNAVAILABLE("SLHD_CHAT_00002", HttpStatus.SERVICE_UNAVAILABLE, "The chat model is not available"),
    CHAT_STREAM_FAILED("SLHD_CHAT_00003", HttpStatus.INTERNAL_SERVER_ERROR,
            "The chat model failed while generating the answer"),
    CHAT_CONTEXT_UNAVAILABLE("SLHD_CHAT_00004", HttpStatus.SERVICE_UNAVAILABLE,
            "The knowledge base is not available"),
    INVALID_CONVERSATION_ID("SLHD_CHAT_00005", HttpStatus.BAD_REQUEST, "The conversation id must be a UUID"),
    CHAT_MEMORY_UNAVAILABLE("SLHD_CHAT_00006", HttpStatus.SERVICE_UNAVAILABLE,
            "The conversation memory is not available"),
    CHAT_BUDGET_EXCEEDED("SLHD_CHAT_00007", HttpStatus.TOO_MANY_REQUESTS,
            "The model usage budget is exhausted, try again later"),
    PROMPT_OVERRIDE_FORBIDDEN("SLHD_CHAT_00008", HttpStatus.FORBIDDEN,
            "Fixing prompt versions is not allowed for this request"),
    INVALID_PROMPT_OVERRIDE("SLHD_CHAT_00009", HttpStatus.BAD_REQUEST,
            "The prompt versions must be a list of name=version with a known name and a version from 1"),
    PROMPT_OVERRIDE_UNAVAILABLE("SLHD_CHAT_00010", HttpStatus.UNPROCESSABLE_CONTENT,
            "A prompt version requested for this answer is not available"),
    INVALID_FEEDBACK("SLHD_CHAT_00011", HttpStatus.BAD_REQUEST, "The feedback request is not valid"),
    FEEDBACK_TURN_NOT_FOUND("SLHD_CHAT_00012", HttpStatus.NOT_FOUND,
            "There is no completed chat turn or feedback with that id"),
    FEEDBACK_ALREADY_PROMOTED("SLHD_CHAT_00013", HttpStatus.CONFLICT,
            "The feedback has already been promoted to a golden case"),
    INVALID_GOLDEN_CASE("SLHD_CHAT_00014", HttpStatus.UNPROCESSABLE_CONTENT,
            "The golden case does not satisfy the dataset rules"),
    FEEDBACK_NOT_NEGATIVE("SLHD_CHAT_00015", HttpStatus.UNPROCESSABLE_CONTENT,
            "Only negative feedback can be promoted to a golden case"),
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
