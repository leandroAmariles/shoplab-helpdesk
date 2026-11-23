package com.shoplab.helpdesk.chat.domain.exception;

import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;

/** Only negative (DOWN) feedback can be promoted to a golden case. */
public class FeedbackNotNegativeException extends BusinessException {

    public FeedbackNotNegativeException() {
        super(ResponseCodeEnum.FEEDBACK_NOT_NEGATIVE);
    }
}
