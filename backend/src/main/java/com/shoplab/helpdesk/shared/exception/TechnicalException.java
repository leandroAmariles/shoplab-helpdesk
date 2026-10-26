package com.shoplab.helpdesk.shared.exception;

/** An external dependency or the infrastructure failed. */
public class TechnicalException extends HelpdeskException {

    public TechnicalException(ResponseCodeEnum responseCode, Throwable cause) {
        super(responseCode, cause);
    }
}
