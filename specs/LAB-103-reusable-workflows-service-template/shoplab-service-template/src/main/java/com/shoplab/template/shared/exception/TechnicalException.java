package com.shoplab.template.shared.exception;

/**
 * Excepción para errores técnicos o fallos de infraestructura (rango 5xx).
 */
public class TechnicalException extends RuntimeException {

    private final ResponseCodeEnum errorCode;

    public TechnicalException(ResponseCodeEnum errorCode) {
        super(errorCode.getDescription());
        this.errorCode = errorCode;
    }

    public TechnicalException(ResponseCodeEnum errorCode, Throwable cause) {
        super(errorCode.getDescription(), cause);
        this.errorCode = errorCode;
    }

    public ResponseCodeEnum getErrorCode() {
        return errorCode;
    }
}
