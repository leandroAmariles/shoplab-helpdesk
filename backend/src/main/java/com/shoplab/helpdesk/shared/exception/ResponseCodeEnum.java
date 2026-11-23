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
    DOCUMENT_UNSUPPORTED_FORMAT("SLHD_RETR_00001", HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "The document format is not supported: only PDF and Markdown are accepted"),
    DOCUMENT_TOO_LARGE("SLHD_RETR_00002", HttpStatus.CONTENT_TOO_LARGE, "The document is too large"),
    DOCUMENT_INVALID("SLHD_RETR_00003", HttpStatus.UNPROCESSABLE_CONTENT,
            "The document is empty, has no extractable text, is corrupt or cannot be chunked"),
    INGEST_SERVICE_UNAVAILABLE("SLHD_RETR_00004", HttpStatus.SERVICE_UNAVAILABLE,
            "The document parsing service is not available"),
    INGEST_SERVICE_FAILED("SLHD_RETR_00005", HttpStatus.BAD_GATEWAY,
            "The document parsing service failed"),
    EMBEDDING_SERVICE_UNAVAILABLE("SLHD_RETR_00006", HttpStatus.SERVICE_UNAVAILABLE,
            "The embedding model is not available"),
    EMBEDDING_SERVICE_FAILED("SLHD_RETR_00007", HttpStatus.BAD_GATEWAY, "The embedding model failed"),
    EMBEDDING_DIMENSION_MISMATCH("SLHD_RETR_00008", HttpStatus.BAD_GATEWAY,
            "The embedding model returned a vector of an unexpected size"),
    SEARCH_QUERY_INVALID("SLHD_RETR_00009", HttpStatus.UNPROCESSABLE_CONTENT,
            "The search query must not be blank"),
    EMBEDDING_BUDGET_EXCEEDED("SLHD_RETR_00010", HttpStatus.TOO_MANY_REQUESTS,
            "The model usage budget is exhausted, try again later"),
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
