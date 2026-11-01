package com.shoplab.helpdesk.chat.infrastructure.out.metrics;

import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.chat.domain.model.enums.ChatOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.ContextOutcome;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Chat metrics on the Prometheus registry from LAB-102; the only tag has a fixed set of values. */
@Component
public class MicrometerChatMetrics implements ChatMetricsPort {

    private final MeterRegistry registry;
    private final Timer firstToken;
    private final DistributionSummary contextFragments;

    public MicrometerChatMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.firstToken = Timer.builder(ChatConstants.FIRST_TOKEN_METRIC)
                .description("Time from receiving the chat request to the first token")
                .publishPercentileHistogram()
                .register(registry);
        this.contextFragments = DistributionSummary.builder(ChatConstants.CONTEXT_FRAGMENTS_METRIC)
                .description("Fragments of context handed to the model for one answer")
                .register(registry);
    }

    @Override
    public void recordFirstToken(Duration elapsed) {
        firstToken.record(elapsed);
    }

    @Override
    public void recordResponse(ChatOutcome outcome, Duration elapsed) {
        Timer.builder(ChatConstants.RESPONSE_METRIC)
                .description("Duration of a chat response by outcome")
                .tag(ChatConstants.OUTCOME_TAG, outcome.tagValue())
                .publishPercentileHistogram()
                .register(registry)
                .record(elapsed);
    }

    @Override
    public void recordContext(ContextOutcome outcome, int fragments, Duration elapsed) {
        Timer.builder(ChatConstants.CONTEXT_RETRIEVAL_METRIC)
                .description("Time to retrieve and select the context of an answer, by outcome")
                .tag(ChatConstants.OUTCOME_TAG, outcome.tagValue())
                .publishPercentileHistogram()
                .register(registry)
                .record(elapsed);
        if (outcome != ContextOutcome.FAILED) {
            contextFragments.record(fragments);
        }
    }
}
