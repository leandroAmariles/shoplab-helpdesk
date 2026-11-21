package com.shoplab.helpdesk.eval.domain.exception;

import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;

/** Saving evaluation runs is off (no token configured) or the request does not carry the right token. */
public class EvalPersistenceDisabledException extends BusinessException {

    public EvalPersistenceDisabledException() {
        super(ResponseCodeEnum.EVAL_PERSISTENCE_DISABLED);
    }
}
