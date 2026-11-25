package com.shoplab.template.shared.exception;

import com.shoplab.template.shared.logging.SecureLogger;
import com.shoplab.template.shared.model.ErrorDto;
import com.shoplab.template.shared.model.ResponseDto;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manejador global centralizado de excepciones para WebFlux.
 * Garantiza que toda respuesta de error adopte la estructura estándar ResponseDto<T>.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final SecureLogger log = new SecureLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<ResponseDto<Void>>> handleValidation(WebExchangeBindException ex) {
        Map<String, String> attributes = ex.getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        DefaultMessageSourceResolvable::getDefaultMessage,
                        (left, right) -> right
                ));

        ErrorDto error = ErrorDto.of(
                ResponseCodeEnum.INVALID_REQUEST.getCode(),
                ResponseCodeEnum.INVALID_REQUEST.getDescription(),
                attributes
        );

        return Mono.just(ResponseEntity
                .status(ResponseCodeEnum.INVALID_REQUEST.getHttpStatus())
                .body(ResponseDto.failure(error)));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public Mono<ResponseEntity<ResponseDto<Void>>> handleInput(ServerWebInputException ex) {
        log.warn("Invalid input received: {}", ex.getReason());
        ErrorDto error = ErrorDto.of(
                ResponseCodeEnum.INVALID_REQUEST.getCode(),
                ex.getReason() != null ? ex.getReason() : ResponseCodeEnum.INVALID_REQUEST.getDescription()
        );
        return Mono.just(ResponseEntity
                .status(ResponseCodeEnum.INVALID_REQUEST.getHttpStatus())
                .body(ResponseDto.failure(error)));
    }

    @ExceptionHandler(BusinessException.class)
    public Mono<ResponseEntity<ResponseDto<Void>>> handleBusiness(BusinessException ex) {
        ResponseCodeEnum code = ex.getErrorCode();
        log.warn("Business rule violation: code={}, message={}", code.getCode(), ex.getMessage());
        ErrorDto error = ErrorDto.of(code.getCode(), ex.getMessage());
        return Mono.just(ResponseEntity.status(code.getHttpStatus()).body(ResponseDto.failure(error)));
    }

    @ExceptionHandler(TechnicalException.class)
    public Mono<ResponseEntity<ResponseDto<Void>>> handleTechnical(TechnicalException ex) {
        ResponseCodeEnum code = ex.getErrorCode();
        log.error("Technical/Infrastructure failure: code={}", code.getCode(), ex);
        ErrorDto error = ErrorDto.of(code.getCode(), code.getDescription());
        return Mono.just(ResponseEntity.status(code.getHttpStatus()).body(ResponseDto.failure(error)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ResponseDto<Void>>> handleResponseStatus(ResponseStatusException ex) {
        log.warn("Response status exception: status={}, reason={}", ex.getStatusCode(), ex.getReason());
        ErrorDto error = ErrorDto.of(
                ResponseCodeEnum.INVALID_REQUEST.getCode(),
                ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString()
        );
        return Mono.just(ResponseEntity.status(ex.getStatusCode()).body(ResponseDto.failure(error)));
    }

    @ExceptionHandler(Throwable.class)
    public Mono<ResponseEntity<ResponseDto<Void>>> handleFallback(Throwable ex) {
        log.error("Unhandled exception intercepted by fallback handler", ex);
        ErrorDto error = ErrorDto.of(
                ResponseCodeEnum.UNKNOWN_ERROR.getCode(),
                ResponseCodeEnum.UNKNOWN_ERROR.getDescription()
        );
        return Mono.just(ResponseEntity
                .status(ResponseCodeEnum.UNKNOWN_ERROR.getHttpStatus())
                .body(ResponseDto.failure(error)));
    }
}
