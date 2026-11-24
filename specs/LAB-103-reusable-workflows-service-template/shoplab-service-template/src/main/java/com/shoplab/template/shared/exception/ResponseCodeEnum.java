package com.shoplab.template.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * Catálogo centralizado de códigos de error tipados para el microservicio.
 * Convención estándar: <SERVICE_PREFIX>_<SUBSYSTEM>_<NNNNN>
 *   GE   : General
 *   DB   : Persistencia / Base de datos
 *   EXT  : Integración externa / Red
 *   AUTH : Seguridad / Autorización
 */
public enum ResponseCodeEnum {

    // Errores Generales (GE)
    INVALID_REQUEST("TMPL_GE_00001", "Invalid request parameters or payload", HttpStatus.BAD_REQUEST),
    RESOURCE_NOT_FOUND("TMPL_GE_00002", "Requested entity not found", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED("TMPL_GE_00003", "HTTP method not allowed for this resource", HttpStatus.METHOD_NOT_ALLOWED),

    // Errores de Base de Datos y Persistencia (DB)
    DATABASE_ERROR("TMPL_DB_00001", "Database persistence operation failed", HttpStatus.INTERNAL_SERVER_ERROR),
    DUPLICATE_ENTITY("TMPL_DB_00002", "Entity with same unique identifier already exists", HttpStatus.CONFLICT),

    // Errores de Integración Externa (EXT)
    EXTERNAL_SERVICE_FAILURE("TMPL_EXT_00001", "Failure while contacting downstream service", HttpStatus.BAD_GATEWAY),
    TIMEOUT_FAILURE("TMPL_EXT_00002", "Operation timed out waiting for external response", HttpStatus.GATEWAY_TIMEOUT),

    // Errores de Autorización y Seguridad (AUTH)
    UNAUTHORIZED_ACCESS("TMPL_AUTH_00001", "Authentication required or invalid token", HttpStatus.UNAUTHORIZED),
    FORBIDDEN_ROLE("TMPL_AUTH_00002", "Access forbidden for this role", HttpStatus.FORBIDDEN),

    // Error Inesperado Fallback
    UNKNOWN_ERROR("TMPL_GE_99999", "An unexpected internal error occurred", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String description;
    private final HttpStatus httpStatus;

    ResponseCodeEnum(String code, String description, HttpStatus httpStatus) {
        this.code = code;
        this.description = description;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
