package com.shoplab.helpdesk.chat.domain.exception;

import com.shoplab.helpdesk.chat.domain.model.enums.MemoryOperation;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.exception.TechnicalException;

/**
 * The conversation memory could not be read or written. The answer is never generated as if the history had been
 * empty, and the message never carries the conversation's text.
 */
public class ConversationMemoryException extends TechnicalException {

    private final transient MemoryOperation operation;

    public ConversationMemoryException(MemoryOperation operation, Throwable cause) {
        super(ResponseCodeEnum.CHAT_MEMORY_UNAVAILABLE, cause);
        this.operation = operation;
    }

    public MemoryOperation getOperation() {
        return operation;
    }
}
