package com.shoplab.helpdesk.chat.application.port.out;

import com.shoplab.helpdesk.chat.domain.model.enums.ChatOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.CitationOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.ContextOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.MemoryOperation;
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

    /** The conversation memory failed while reading or writing. */
    void recordMemoryFailure(MemoryOperation operation);
}
