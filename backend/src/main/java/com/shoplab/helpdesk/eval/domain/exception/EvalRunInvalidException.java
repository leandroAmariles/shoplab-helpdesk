package com.shoplab.helpdesk.eval.domain.exception;

import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.PublicDetail;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;

/**
 * The run breaks a rule of the data model. The detail names the field or the case and the rule; it never quotes the
 * text of a question or an answer.
 */
public class EvalRunInvalidException extends BusinessException implements PublicDetail {

    private final String detail;

    public EvalRunInvalidException(String detail) {
        super(ResponseCodeEnum.EVAL_RUN_INVALID);
        this.detail = detail;
    }

    @Override
    public String publicDetail() {
        return detail;
    }
}
