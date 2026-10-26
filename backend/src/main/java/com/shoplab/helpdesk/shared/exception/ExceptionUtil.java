package com.shoplab.helpdesk.shared.exception;

/** Builds typed exceptions with the standard code and description. */
public final class ExceptionUtil {

    private ExceptionUtil() {
    }

    public static BusinessException business(ResponseCodeEnum responseCode) {
        return new BusinessException(responseCode);
    }

    public static TechnicalException technical(ResponseCodeEnum responseCode, Throwable cause) {
        return new TechnicalException(responseCode, cause);
    }
}
