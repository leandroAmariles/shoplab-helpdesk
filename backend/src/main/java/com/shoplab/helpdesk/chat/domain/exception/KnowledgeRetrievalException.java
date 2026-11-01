package com.shoplab.helpdesk.chat.domain.exception;

import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.exception.TechnicalException;

/**
 * The context for an answer could not be retrieved (embedding service, database). It is not the chat model failing,
 * and the answer must not be generated as if the context had been there.
 */
public class KnowledgeRetrievalException extends TechnicalException {

    public KnowledgeRetrievalException(Throwable cause) {
        super(ResponseCodeEnum.CHAT_CONTEXT_UNAVAILABLE, cause);
    }
}
