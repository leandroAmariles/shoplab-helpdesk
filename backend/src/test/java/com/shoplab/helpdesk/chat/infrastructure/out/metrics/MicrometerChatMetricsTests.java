package com.shoplab.helpdesk.chat.infrastructure.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.model.enums.ChatOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.ContextOutcome;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MicrometerChatMetricsTests {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerChatMetrics metrics = new MicrometerChatMetrics(registry);

    @Test
    void recordsTheTimeToFirstTokenInItsOwnTimer() {
        metrics.recordFirstToken(Duration.ofMillis(250));

        assertThat(registry.get("shoplab.chat.first.token").timer().count()).isEqualTo(1);
        assertThat(registry.get("shoplab.chat.first.token").timer().totalTime(TimeUnit.MILLISECONDS))
                .isEqualTo(250.0);
    }

    @Test
    void recordsTheResponseDurationTaggedWithTheLowerCaseOutcome() {
        metrics.recordResponse(ChatOutcome.COMPLETED, Duration.ofSeconds(2));
        metrics.recordResponse(ChatOutcome.ERROR, Duration.ofSeconds(1));
        metrics.recordResponse(ChatOutcome.CANCELLED, Duration.ofSeconds(1));
        metrics.recordResponse(ChatOutcome.COMPLETED, Duration.ofSeconds(3));

        assertThat(registry.get("shoplab.chat.response").tag("outcome", "completed").timer().count()).isEqualTo(2);
        assertThat(registry.get("shoplab.chat.response").tag("outcome", "error").timer().count()).isEqualTo(1);
        assertThat(registry.get("shoplab.chat.response").tag("outcome", "cancelled").timer().count()).isEqualTo(1);
    }

    @Test
    void recordsTheContextRetrievalTimeByOutcomeAndTheFragmentsDelivered() {
        metrics.recordContext(ContextOutcome.WITH_CONTEXT, 3, Duration.ofMillis(120));
        metrics.recordContext(ContextOutcome.WITH_CONTEXT, 1, Duration.ofMillis(80));
        metrics.recordContext(ContextOutcome.NO_CONTEXT, 0, Duration.ofMillis(50));
        metrics.recordContext(ContextOutcome.FAILED, 0, Duration.ofMillis(10));

        assertThat(registry.get("shoplab.chat.context.retrieval").tag("outcome", "with_context").timer().count())
                .isEqualTo(2);
        assertThat(registry.get("shoplab.chat.context.retrieval").tag("outcome", "no_context").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("shoplab.chat.context.retrieval").tag("outcome", "failed").timer().count())
                .isEqualTo(1);
        // a failed retrieval delivered nothing and is not a sample of "fragments per answer"
        assertThat(registry.get("shoplab.chat.context.fragments").summary().count()).isEqualTo(3);
        assertThat(registry.get("shoplab.chat.context.fragments").summary().totalAmount()).isEqualTo(4.0);
    }

    @Test
    void onlyTheOutcomeTagIsUsedSoCardinalityStaysBounded() {
        metrics.recordContext(ContextOutcome.WITH_CONTEXT, 2, Duration.ofMillis(5));
        metrics.recordResponse(ChatOutcome.COMPLETED, Duration.ofSeconds(1));
        metrics.recordFirstToken(Duration.ofMillis(100));

        assertThat(registry.getMeters()).extracting(Meter::getId).allSatisfy(id ->
                assertThat(id.getTags()).extracting(tag -> tag.getKey()).isSubsetOf("outcome"));
    }
}
