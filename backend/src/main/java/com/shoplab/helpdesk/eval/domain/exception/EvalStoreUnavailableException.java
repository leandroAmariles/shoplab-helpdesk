package com.shoplab.helpdesk.eval.domain.exception;

import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.exception.TechnicalException;

/** The database of the evaluation history failed; nothing of the run was saved. */
public class EvalStoreUnavailableException extends TechnicalException {

    public EvalStoreUnavailableException(Throwable cause) {
        super(ResponseCodeEnum.EVAL_STORE_UNAVAILABLE, cause);
    }
}
