package com.shoplab.helpdesk.chat.application.port.out;

import com.shoplab.helpdesk.chat.domain.model.enums.ChatOutcome;
import java.time.Duration;

public interface ChatMetricsPort {

    void recordFirstToken(Duration elapsed);

    void recordResponse(ChatOutcome outcome, Duration elapsed);
}
