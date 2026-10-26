package com.shoplab.helpdesk.shared.exception;

import org.springframework.http.HttpStatus;

/** Single catalogue of error codes, format {@code <SERVICE>_XXXX_NNNNN} with service prefix {@code SLHD}. */
public enum ResponseCodeEnum {

    INVALID_CHAT_MESSAGE("SLHD_CHAT_00001", HttpStatus.BAD_REQUEST, "The message must not be blank or too long"),
    CHAT_MODEL_UNAVAILABLE("SLHD_CHAT_00002", HttpStatus.SERVICE_UNAVAILABLE, "The chat model is not available"),
    CHAT_STREAM_FAILED("SLHD_CHAT_00003", HttpStatus.INTERNAL_SERVER_ERROR,
            "The chat model failed while generating the answer"),
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
