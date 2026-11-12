package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.Collection;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps the exporter towards Langfuse so that a batch that could not be delivered leaves a trace in the operation of
 * the service and never in the chat (specs/LAB-64, FR-022): it counts the failure in
 * {@code shoplab.chat.trace.export.failures} and warns at most once per minute with a fixed text, so that neither the
 * URL, the credentials nor any content of the conversation can reach the log.
 */
public final class ExportFailureReporter implements SpanExporter {

    public static final String FAILURES_METER = "shoplab.chat.trace.export.failures";

    private static final Logger LOG = LoggerFactory.getLogger(ExportFailureReporter.class);
    private static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);

    private final SpanExporter delegate;
    private final Counter failures;
    private final WarnThrottle throttle = new WarnThrottle(WARNING_INTERVAL);

    public ExportFailureReporter(SpanExporter delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.failures = Counter.builder(FAILURES_METER)
                .description("Batches of chat traces that could not be delivered to Langfuse")
                .register(registry);
    }

    @Override
    public CompletableResultCode export(@NonNull Collection<SpanData> spans) {
        CompletableResultCode result;
        try {
            result = delegate.export(spans);
        } catch (RuntimeException e) {
            failed(e.getClass().getSimpleName());
            return CompletableResultCode.ofFailure();
        }
        return result.whenComplete(() -> {
            if (!result.isSuccess()) {
                failed("rejected or unreachable");
            }
        });
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    private void failed(String reason) {
        failures.increment();
        if (throttle.allow()) {
            LOG.warn("A batch of chat traces could not be delivered to Langfuse ({}); the chat is not affected",
                    reason);
        }
    }
}
