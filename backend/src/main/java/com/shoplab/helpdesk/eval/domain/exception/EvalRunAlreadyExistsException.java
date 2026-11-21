package com.shoplab.helpdesk.eval.domain.exception;

import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;

/** A run with that identifier is already saved; it is never overwritten or duplicated. */
public class EvalRunAlreadyExistsException extends BusinessException {

    public EvalRunAlreadyExistsException() {
        super(ResponseCodeEnum.EVAL_RUN_ALREADY_EXISTS);
    }
}
