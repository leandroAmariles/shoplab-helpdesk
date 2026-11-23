package com.shoplab.helpdesk.eval.domain.exception;

import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;

/** Two runs are asked to be compared but they are not of the same kind: their metrics are not the same. */
public class EvalRunsNotComparableException extends BusinessException {

    public EvalRunsNotComparableException() {
        super(ResponseCodeEnum.EVAL_RUNS_NOT_COMPARABLE);
    }
}
