package com.shoplab.helpdesk.eval.domain.exception;

import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;

/** There is no saved run with that identifier. */
public class EvalRunNotFoundException extends BusinessException {

    public EvalRunNotFoundException() {
        super(ResponseCodeEnum.EVAL_RUN_NOT_FOUND);
    }
}
