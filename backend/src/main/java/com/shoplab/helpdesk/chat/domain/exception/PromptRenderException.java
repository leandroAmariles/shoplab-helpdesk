package com.shoplab.helpdesk.chat.domain.exception;

/** A prompt template names a variable that was not given a value. The message names the variable, never the text. */
public class PromptRenderException extends IllegalArgumentException {

    public PromptRenderException(String variable) {
        super("The template uses the variable {" + variable + "}, which has no value");
    }
}
