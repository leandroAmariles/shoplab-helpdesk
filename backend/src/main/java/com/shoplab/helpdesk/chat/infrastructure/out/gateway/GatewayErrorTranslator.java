package com.shoplab.helpdesk.chat.infrastructure.out.gateway;

import com.openai.errors.OpenAIServiceException;
import com.shoplab.helpdesk.chat.domain.exception.ChatBudgetExceededException;
import com.shoplab.helpdesk.chat.domain.exception.ChatModelException;
import com.shoplab.helpdesk.chat.domain.model.constants.GatewayConstants;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns what the gateway answers into the typed errors of the chat module (FR-004). The user only ever sees the
 * default message of the code: the gateway's own text can quote fragments of the prompt, so it is never logged or
 * returned; only the HTTP status and the {@code type}/{@code code} of the error are.
 */
final class GatewayErrorTranslator {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayErrorTranslator.class);
    private static final int UNAUTHORIZED = 401;
    private static final int FORBIDDEN = 403;

    private GatewayErrorTranslator() {
    }

    /**
     * An exhausted budget is {@link ChatBudgetExceededException}; anything else (the gateway down, a rejected key, an
     * unknown model, the fallback exhausted) is {@link ChatModelException}, which the use case turns into "model not
     * available".
     */
    static HelpdeskException translate(Throwable error) {
        if (error instanceof HelpdeskException typed) {
            return typed;
        }
        Optional<OpenAIServiceException> service = serviceError(error);
        if (service.isPresent()) {
            OpenAIServiceException failure = service.get();
            String type = failure.type().orElse("");
            LOG.warn("The model gateway rejected the request: status={}, type={}, code={}", failure.statusCode(),
                    type, failure.code().orElse(""));
            if (GatewayConstants.BUDGET_EXCEEDED_ERROR_TYPE.equals(type)) {
                return new ChatBudgetExceededException(error);
            }
            if (failure.statusCode() == UNAUTHORIZED || failure.statusCode() == FORBIDDEN) {
                LOG.error("The model gateway does not accept the virtual key of the backend; check "
                        + "SPRING_AI_OPENAI_API_KEY");
            }
        }
        return new ChatModelException(error);
    }

    private static Optional<OpenAIServiceException> serviceError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof OpenAIServiceException service) {
                return Optional.of(service);
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return Optional.empty();
    }
}
