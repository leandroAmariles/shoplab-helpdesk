package com.shoplab.helpdesk.chat.domain.exception;

import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.exception.TechnicalException;

/**
 * The chat model failed, before or after answering started. The adapter only says that; the use case knows whether
 * the stream had started and picks the code (503 before the first fragment, a final error event after it).
 */
public class ChatModelException extends TechnicalException {

    public ChatModelException(Throwable cause) {
        super(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE, cause);
    }
}
