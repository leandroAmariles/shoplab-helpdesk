package com.shoplab.helpdesk.chat.infrastructure.out.metrics;

import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import com.shoplab.helpdesk.chat.domain.model.enums.ChatOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.CitationOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.ContextOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.MemoryOperation;
import io.micrometer.core.instrument.Counter;
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
    private final DistributionSummary historyMessages;
    private final Counter historyTrimmed;

    public MicrometerChatMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.firstToken = Timer.builder(ChatConstants.FIRST_TOKEN_METRIC)
                .description("Time from receiving the chat request to the first token")
                .publishPercentileHistogram()
                .register(registry);
        this.contextFragments = DistributionSummary.builder(ChatConstants.CONTEXT_FRAGMENTS_METRIC)
                .description("Fragments of context handed to the model for one answer")
                .register(registry);
        this.historyMessages = DistributionSummary.builder(ChatConstants.HISTORY_MESSAGES_METRIC)
                .description("Messages of the conversation history handed to the model for one answer")
                .register(registry);
        this.historyTrimmed = Counter.builder(ChatConstants.HISTORY_TRIMMED_METRIC)
                .description("Answers for which part of the conversation history was left out by the limits")
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

    @Override
    public void recordCitations(CitationOutcome outcome) {
        Counter.builder(ChatConstants.CITATIONS_METRIC)
                .description("Answers given with context, by the quality of their citations")
                .tag(ChatConstants.OUTCOME_TAG, outcome.tagValue())
                .register(registry)
                .increment();
    }

    @Override
    public void recordHistory(int messages, boolean trimmed) {
        historyMessages.record(messages);
        if (trimmed) {
            historyTrimmed.increment();
        }
    }

    @Override
    public void recordMemoryFailure(MemoryOperation operation) {
        Counter.builder(ChatConstants.MEMORY_FAILURES_METRIC)
                .description("Failures of the conversation memory, by operation")
                .tag(ChatConstants.OPERATION_TAG, operation.tagValue())
                .register(registry)
                .increment();
    }
}
