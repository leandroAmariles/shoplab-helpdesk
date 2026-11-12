package com.shoplab.helpdesk.chat.domain.model;

import com.shoplab.helpdesk.chat.domain.model.enums.FailureStage;

/**
 * Why a turn failed.
 *
 * @param code  the error code the customer received
 * @param stage the step that failed
 */
public record TurnFailure(String code, FailureStage stage) {

    public TurnFailure {
        if (code == null || code.isBlank() || stage == null) {
            throw new IllegalArgumentException("A failure needs the code of the error and the stage that failed");
        }
    }
}
