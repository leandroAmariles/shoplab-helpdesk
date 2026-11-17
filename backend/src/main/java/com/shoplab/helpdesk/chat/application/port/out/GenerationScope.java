package com.shoplab.helpdesk.chat.application.port.out;

import com.shoplab.helpdesk.chat.domain.model.ServedModel;
import com.shoplab.helpdesk.chat.domain.model.TokenUsage;

/** One call to the model inside a turn. It ends exactly once, whichever of the three ends comes first. */
public interface GenerationScope {

    GenerationScope NONE = new GenerationScope() {
        @Override
        public void firstToken() {
            // nothing is recorded
        }

        @Override
        public void complete(TokenUsage usage, String answer) {
            // nothing is recorded
        }

        @Override
        public void fail(Throwable error) {
            // nothing is recorded
        }

        @Override
        public void cancel(String partialAnswer) {
            // nothing is recorded
        }
    };

    /** The first fragment of the answer arrived. */
    void firstToken();

    /** The model finished; {@code usage} may be unknown. */
    void complete(TokenUsage usage, String answer);

    /**
     * The model finished and the gateway said which model really answered, which may not be the primary one (LAB-84).
     * Scopes that do not record the served model ignore it.
     */
    default void complete(TokenUsage usage, String answer, ServedModel served) {
        complete(usage, answer);
    }

    /** The model failed. */
    void fail(Throwable error);

    /** The subscription was cancelled before the model finished; {@code partialAnswer} is what had arrived. */
    void cancel(String partialAnswer);
}
