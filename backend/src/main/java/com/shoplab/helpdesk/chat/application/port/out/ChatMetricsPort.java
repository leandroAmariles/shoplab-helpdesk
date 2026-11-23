package com.shoplab.helpdesk.chat.application.port.out;

import com.shoplab.helpdesk.chat.domain.model.enums.ChatOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.CitationOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.ContextOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.FeedbackRating;
import com.shoplab.helpdesk.chat.domain.model.enums.MemoryOperation;
import com.shoplab.helpdesk.chat.domain.model.enums.TurnKind;
import java.time.Duration;

public interface ChatMetricsPort {

    void recordFirstToken(Duration elapsed);

    void recordResponse(ChatOutcome outcome, Duration elapsed);

    /** Retrieving and selecting the context of one answer: how it ended, fragments handed over, time taken. */
    void recordContext(ContextOutcome outcome, int fragments, Duration elapsed);

    /** How well one answer given with context cites its sources: counted once per completed answer. */
    void recordCitations(CitationOutcome outcome);

    /** The history handed to the model for one answer: its messages, and whether some turns were left out. */
    void recordHistory(int messages, boolean trimmed);

    /** A turn attended by the chat, counted once by its kind; a failed retrieval is not a kind (LAB-114). */
    void recordTurn(TurnKind kind);

    /** The conversation memory failed while reading or writing. */
    void recordMemoryFailure(MemoryOperation operation);

    /** A customer rating (UP or DOWN) was recorded or updated in {@code chat.feedback}. */
    default void recordFeedback(FeedbackRating rating) {
        // optional in test doubles
    }

    /** A negative feedback turn was promoted to a golden dataset case. */
    default void recordFeedbackPromoted() {
        // optional in test doubles
    }
}
