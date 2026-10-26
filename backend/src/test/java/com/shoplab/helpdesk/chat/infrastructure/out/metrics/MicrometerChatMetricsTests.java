package com.shoplab.helpdesk.chat.infrastructure.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.model.enums.ChatOutcome;
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
    void onlyTheOutcomeTagIsUsedSoCardinalityStaysBounded() {
        metrics.recordResponse(ChatOutcome.COMPLETED, Duration.ofSeconds(1));
        metrics.recordFirstToken(Duration.ofMillis(100));

        assertThat(registry.getMeters()).extracting(Meter::getId).allSatisfy(id ->
                assertThat(id.getTags()).extracting(tag -> tag.getKey()).isSubsetOf("outcome"));
    }
}
