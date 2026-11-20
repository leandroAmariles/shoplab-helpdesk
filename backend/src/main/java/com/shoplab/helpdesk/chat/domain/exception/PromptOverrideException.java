package com.shoplab.helpdesk.chat.domain.exception;

import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;

/**
 * The versions a request fixes cannot be used: the header is malformed (400, before the stream opens) or a version does
 * not exist, is not valid or Langfuse did not answer (an {@code error} event of the stream). It is never answered with
 * the {@code production} version or the default text: an evaluation measured with other prompts would be a wrong result.
 */
public class PromptOverrideException extends BusinessException {

    public PromptOverrideException(ResponseCodeEnum responseCode) {
        super(responseCode);
    }

    public static PromptOverrideException malformed() {
        return new PromptOverrideException(ResponseCodeEnum.INVALID_PROMPT_OVERRIDE);
    }

    public static PromptOverrideException unavailable() {
        return new PromptOverrideException(ResponseCodeEnum.PROMPT_OVERRIDE_UNAVAILABLE);
    }
}
