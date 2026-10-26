package com.shoplab.helpdesk.shared.exception;

/** A rule of the business or a bad input was violated. */
public class BusinessException extends HelpdeskException {

    public BusinessException(ResponseCodeEnum responseCode) {
        super(responseCode, null);
    }
}
