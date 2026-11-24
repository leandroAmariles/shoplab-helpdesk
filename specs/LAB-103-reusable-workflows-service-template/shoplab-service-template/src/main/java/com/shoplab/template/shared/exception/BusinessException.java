package com.shoplab.template.shared.exception;

/**
 * Excepción para errores de negocio o validación de clientes (rango 4xx).
 */
public class BusinessException extends RuntimeException {

    private final ResponseCodeEnum errorCode;

    public BusinessException(ResponseCodeEnum errorCode) {
        super(errorCode.getDescription());
        this.errorCode = errorCode;
    }

    public BusinessException(ResponseCodeEnum errorCode, String customMessage) {
        super(customMessage);
        this.errorCode = errorCode;
    }

    public BusinessException(ResponseCodeEnum errorCode, Throwable cause) {
        super(errorCode.getDescription(), cause);
        this.errorCode = errorCode;
    }

    public ResponseCodeEnum getErrorCode() {
        return errorCode;
    }
}
