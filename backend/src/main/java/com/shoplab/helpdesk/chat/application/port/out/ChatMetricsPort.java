package com.shoplab.helpdesk.chat.application.port.out;

import com.shoplab.helpdesk.chat.domain.model.enums.ChatOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.ContextOutcome;
import java.time.Duration;

public interface ChatMetricsPort {

    void recordFirstToken(Duration elapsed);

    void recordResponse(ChatOutcome outcome, Duration elapsed);

    /** Retrieving and selecting the context of one answer: how it ended, fragments handed over, time taken. */
    void recordContext(ContextOutcome outcome, int fragments, Duration elapsed);
}
