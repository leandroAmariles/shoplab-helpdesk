package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InternalTelemetryVersion;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessorBuilder;
import java.time.Duration;
import java.util.Collection;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the traces that the queue had no room for visible (specs/LAB-64, FR-021): the batch processor of the SDK
 * counts them in its own {@code processedSpans} metric ({@code dropped=true}); this reads that metric and adds the new
 * drops to the Micrometer counter {@code shoplab.chat.trace.dropped}, with a warning at most once per minute and
 * without any content of the conversation. Plug it in with {@link #meterProvider(MeterRegistry)} and
 * {@link BatchSpanProcessorBuilder#setMeterProvider(io.opentelemetry.api.metrics.MeterProvider)}.
 */
public final class DroppedSpansReporter implements MetricExporter {

    public static final String DROPPED_METER = "shoplab.chat.trace.dropped";

    private static final Logger LOG = LoggerFactory.getLogger(DroppedSpansReporter.class);
    private static final String PROCESSED_SPANS = "processedSpans";
    private static final AttributeKey<Boolean> DROPPED = AttributeKey.booleanKey("dropped");
    private static final Duration REPORT_INTERVAL = Duration.ofSeconds(5);
    private static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);

    private final Counter dropped;
    private final WarnThrottle throttle = new WarnThrottle(WARNING_INTERVAL);
    private long reported;

    private DroppedSpansReporter(MeterRegistry registry) {
        this.dropped = Counter.builder(DROPPED_METER)
                .description("Chat traces dropped because the queue towards Langfuse was full")
                .register(registry);
    }

    /** A meter provider whose only job is to feed the counter; the SDK is told to emit the legacy metrics. */
    public static SdkMeterProvider meterProvider(MeterRegistry registry) {
        return SdkMeterProvider.builder()
                .registerMetricReader(PeriodicMetricReader.builder(new DroppedSpansReporter(registry))
                        .setInterval(REPORT_INTERVAL).build())
                .build();
    }

    /** The telemetry version the batch processor must use so that {@code processedSpans} exists. */
    public static InternalTelemetryVersion telemetryVersion() {
        return InternalTelemetryVersion.LEGACY;
    }

    @Override
    public CompletableResultCode export(@NonNull Collection<MetricData> metrics) {
        try {
            long total = metrics.stream()
                    .filter(metric -> PROCESSED_SPANS.equals(metric.getName())
                            && metric.getType() == MetricDataType.LONG_SUM)
                    .flatMap(metric -> metric.getLongSumData().getPoints().stream())
                    .filter(point -> Boolean.TRUE.equals(point.getAttributes().get(DROPPED)))
                    .mapToLong(LongPointData::getValue).sum();
            long fresh = total - reported;
            reported = total;
            if (fresh > 0) {
                dropped.increment(fresh);
                if (throttle.allow()) {
                    LOG.warn("{} chat traces were dropped because the queue towards Langfuse is full; "
                            + "the chat is not affected", fresh);
                }
            }
            return CompletableResultCode.ofSuccess();
        } catch (RuntimeException e) {
            LOG.warn("The count of dropped chat traces failed: {}", e.getClass().getSimpleName());
            return CompletableResultCode.ofFailure();
        }
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(@NonNull InstrumentType instrumentType) {
        return AggregationTemporality.CUMULATIVE;
    }
}
