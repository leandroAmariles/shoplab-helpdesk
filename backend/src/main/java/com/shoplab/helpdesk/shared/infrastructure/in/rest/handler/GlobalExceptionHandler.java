package com.shoplab.helpdesk.shared.infrastructure.in.rest.handler;

import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The only place where exceptions are mapped to HTTP responses. Errors are always JSON, even when the client asked
 * for {@code text/event-stream}, otherwise content negotiation would answer 406 to a streaming client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(HelpdeskException.class)
    public ResponseEntity<ErrorResponse> handleTyped(HelpdeskException exception) {
        ResponseCodeEnum responseCode = exception.getResponseCode();
        if (responseCode.getHttpStatus().is5xxServerError()) {
            LOG.error("Request failed: code={}", responseCode.getCode(), exception);
        } else {
            LOG.warn("Request rejected: code={}", responseCode.getCode());
        }
        return json(responseCode);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        LOG.error("Unexpected error: code={}", ResponseCodeEnum.UNEXPECTED_ERROR.getCode(), exception);
        return json(ResponseCodeEnum.UNEXPECTED_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        LOG.warn("Request body not readable: code={}", ResponseCodeEnum.INVALID_REQUEST_BODY.getCode());
        ResponseCodeEnum responseCode = ResponseCodeEnum.INVALID_REQUEST_BODY;
        return ResponseEntity.status(responseCode.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(responseCode.getCode(), responseCode.getDefaultMessage()));
    }

    private ResponseEntity<ErrorResponse> json(ResponseCodeEnum responseCode) {
        return ResponseEntity.status(responseCode.getHttpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(responseCode.getCode(), responseCode.getDefaultMessage()));
    }
}
