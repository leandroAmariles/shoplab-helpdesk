package com.shoplab.helpdesk.chat.domain.exception;

import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.exception.TechnicalException;

/**
 * The gateway refused the request because the virtual key of the backend has spent its budget. It is not "model
 * unavailable": waiting for the period to reset fixes it, restarting the model does not.
 */
public class ChatBudgetExceededException extends TechnicalException {

    public ChatBudgetExceededException(Throwable cause) {
        super(ResponseCodeEnum.CHAT_BUDGET_EXCEEDED, cause);
    }
}
