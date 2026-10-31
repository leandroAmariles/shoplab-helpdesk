package com.shoplab.helpdesk.retrieval.infrastructure.out.ingest;

import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.net.ConnectException;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;

/**
 * Translates what goes wrong when calling the ingest service into typed exceptions: a 4xx means the service rejected
 * the document (a business error), anything else means the service is not working (a technical one).
 */
@Component
public class IngestErrorMapper {

    /** The service answered with an error status; {@code code} is the code of its body, kept for the diagnosis. */
    public HelpdeskException fromRejection(HttpStatusCode status, String code) {
        if (status.value() == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) {
            return ExceptionUtil.business(ResponseCodeEnum.DOCUMENT_UNSUPPORTED_FORMAT);
        }
        if (status.value() == HttpStatus.CONTENT_TOO_LARGE.value()) {
            return ExceptionUtil.business(ResponseCodeEnum.DOCUMENT_TOO_LARGE);
        }
        if (status.is4xxClientError()) {
            return ExceptionUtil.business(ResponseCodeEnum.DOCUMENT_INVALID);
        }
        return ExceptionUtil.technical(ResponseCodeEnum.INGEST_SERVICE_FAILED,
                new IllegalStateException("The ingest service answered " + status.value() + " (" + code + ")"));
    }

    /** The call itself failed: no connection, no answer in time, or an answer that cannot be read. */
    public HelpdeskException fromTransportFailure(Throwable error) {
        if (error instanceof TimeoutException || error instanceof WebClientRequestException
                || error.getCause() instanceof ConnectException) {
            return ExceptionUtil.technical(ResponseCodeEnum.INGEST_SERVICE_UNAVAILABLE, error);
        }
        return ExceptionUtil.technical(ResponseCodeEnum.INGEST_SERVICE_FAILED, error);
    }
}
