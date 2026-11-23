package com.shoplab.helpdesk.chat.domain.exception;

import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.PublicDetail;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;

/**
 * The proposed golden case breaks a rule of the LAB-27 dataset. The detail names the rule code ({@code G-...}) and a
 * safe explanation; it never quotes the text of a question or an answer.
 */
public class InvalidGoldenCaseException extends BusinessException implements PublicDetail {

    private final String detail;

    public InvalidGoldenCaseException(String detail) {
        super(ResponseCodeEnum.INVALID_GOLDEN_CASE);
        this.detail = detail;
    }

    @Override
    public String publicDetail() {
        return detail;
    }
}
