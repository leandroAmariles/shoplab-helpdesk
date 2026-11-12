package com.shoplab.helpdesk.chat.support;

import com.shoplab.helpdesk.chat.application.port.out.ChatTracePort;
import com.shoplab.helpdesk.chat.infrastructure.out.tracing.LangfuseChatTrace;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Replaces the OTLP exporter of the chat traces with one that keeps the spans in memory, so the tests see what would
 * have been sent to Langfuse without a Langfuse (specs/LAB-64, FR-030).
 */
@TestConfiguration
public class RecordedSpansConfig {

    public static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

    public static final String PUBLIC_KEY = "pk-lf-test";
    public static final String SECRET_KEY = "sk-lf-test-secret";

    @Bean
    @Primary
    SpanExporter recordedSpanExporter() {
        return SPANS;
    }

    /** Turns the tracing on for a test class, with fast deliveries. */
    public static void enableTracing(DynamicPropertyRegistry registry) {
        registry.add("shoplab.chat.tracing.enabled", () -> "true");
        registry.add("shoplab.chat.tracing.public-key", () -> PUBLIC_KEY);
        registry.add("shoplab.chat.tracing.secret-key", () -> SECRET_KEY);
        registry.add("shoplab.chat.tracing.schedule-delay", () -> "100ms");
        registry.add("shoplab.chat.tracing.environment", () -> "test");
    }

    /** Sends what is pending to the exporter, so that the spans of the turn that just ended can be read. */
    public static void flush(ChatTracePort trace) {
        ((LangfuseChatTrace) trace).forceFlush();
    }

    public static SpanData span(String name) {
        return SPANS.getFinishedSpanItems().stream().filter(item -> item.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no span " + name + " in "
                        + SPANS.getFinishedSpanItems().stream().map(SpanData::getName).toList()));
    }

    public static String attr(SpanData span, String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }
}
