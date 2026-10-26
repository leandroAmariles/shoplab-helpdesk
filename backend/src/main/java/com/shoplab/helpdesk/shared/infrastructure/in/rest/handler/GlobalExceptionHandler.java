package com.shoplab.helpdesk.shared.infrastructure.in.rest.handler;

import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.dto.ErrorResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

/**
 * The only place where exceptions are mapped to HTTP responses. Errors are always JSON, even when the client asked
 * for {@code text/event-stream}: the handler mapping publishes {@code text/event-stream} as the only producible type
 * of the chat route and content negotiation would otherwise answer 406 to a streaming client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HelpdeskException.class)
    public ResponseEntity<ErrorResponse> handleTyped(HelpdeskException exception, ServerWebExchange exchange) {
        ResponseCodeEnum responseCode = exception.getResponseCode();
        if (responseCode.getHttpStatus().is5xxServerError()) {
            LOG.error("Request failed: code={}", responseCode.getCode(), exception);
        } else {
            LOG.warn("Request rejected: code={}", responseCode.getCode());
        }
        return json(responseCode, exchange);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, ServerWebExchange exchange) {
        LOG.error("Unexpected error: code={}", ResponseCodeEnum.UNEXPECTED_ERROR.getCode(), exception);
        return json(ResponseCodeEnum.UNEXPECTED_ERROR, exchange);
    }

    @Override
    protected Mono<ResponseEntity<Object>> handleServerWebInputException(@NonNull ServerWebInputException exception,
            @NonNull HttpHeaders headers, @NonNull HttpStatusCode status, @NonNull ServerWebExchange exchange) {
        ResponseCodeEnum responseCode = ResponseCodeEnum.INVALID_REQUEST_BODY;
        LOG.warn("Request body not readable: code={}", responseCode.getCode());
        exchange.getAttributes().remove(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
        return Mono.just(ResponseEntity.status(responseCode.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(responseCode.getCode(), responseCode.getDefaultMessage())));
    }

    private ResponseEntity<ErrorResponse> json(ResponseCodeEnum responseCode, ServerWebExchange exchange) {
        exchange.getAttributes().remove(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE);
        return ResponseEntity.status(responseCode.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(responseCode.getCode(), responseCode.getDefaultMessage()));
    }
}
