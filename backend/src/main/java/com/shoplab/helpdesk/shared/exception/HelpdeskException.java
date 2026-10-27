package com.shoplab.helpdesk.shared.exception;

/** Base of the typed exceptions; the response code decides the HTTP status and the public description. */
public abstract class HelpdeskException extends RuntimeException {

    private final transient ResponseCodeEnum responseCode;

    protected HelpdeskException(ResponseCodeEnum responseCode, Throwable cause) {
        super(responseCode.getDefaultMessage(), cause);
        this.responseCode = responseCode;
    }

    /** For failures nobody sees over HTTP (start-up), where the message may carry more detail than the public one. */
    protected HelpdeskException(ResponseCodeEnum responseCode, String message, Throwable cause) {
        super(message, cause);
        this.responseCode = responseCode;
    }

    public ResponseCodeEnum getResponseCode() {
        return responseCode;
    }
}
