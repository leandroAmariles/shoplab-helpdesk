package com.shoplab.helpdesk.chat.application.port.out;

import com.shoplab.helpdesk.chat.domain.model.PromptMessage;
import com.shoplab.helpdesk.chat.domain.model.RetrievalTrace;
import com.shoplab.helpdesk.chat.domain.model.ToolCallTrace;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What a turn can report while it runs. No method throws: a failure of the recording must not reach the chat.
 */
public interface TurnTrace {

    /** A trace that ignores everything, for turns that nobody records. */
    TurnTrace NONE = new TurnTrace() {
        @Override
        public void historyTurns(int turns) {
            // nothing is recorded
        }

        @Override
        public void retrieval(RetrievalTrace retrieval, Duration elapsed) {
            // nothing is recorded
        }

        @Override
        public void fixedMessageAnswer() {
            // nothing is recorded
        }

        @Override
        public GenerationScope startGeneration(String model, Map<String, Object> parameters,
                List<PromptMessage> messages) {
            return GenerationScope.NONE;
        }

        @Override
        public void toolCall(ToolCallTrace call, Duration elapsed) {
            // nothing is recorded
        }
    };

    /** Earlier turns of the conversation that went into the prompt. */
    void historyTurns(int turns);

    /** The search for context: what was searched, what was found and what was handed to the model. */
    void retrieval(RetrievalTrace retrieval, Duration elapsed);

    /** The turn is answered with the configured message, without calling the model. */
    void fixedMessageAnswer();

    /** The model is about to be called with these messages. */
    GenerationScope startGeneration(String model, Map<String, Object> parameters, List<PromptMessage> messages);

    /** The model called a tool. */
    void toolCall(ToolCallTrace call, Duration elapsed);
}
