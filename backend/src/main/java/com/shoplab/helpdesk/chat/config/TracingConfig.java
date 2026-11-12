package com.shoplab.helpdesk.chat.config;

import com.shoplab.helpdesk.chat.application.port.out.ChatTracePort;
import com.shoplab.helpdesk.chat.infrastructure.out.tracing.DerivedTraceIdGenerator;
import com.shoplab.helpdesk.chat.infrastructure.out.tracing.DroppedSpansReporter;
import com.shoplab.helpdesk.chat.infrastructure.out.tracing.ExportFailureReporter;
import com.shoplab.helpdesk.chat.infrastructure.out.tracing.LangfuseChatTrace;
import com.shoplab.helpdesk.chat.infrastructure.out.tracing.NoOpChatTrace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses what records the chat turns (LAB-64). Without the setting, nothing is created and nothing is sent: no
 * exporter, no thread, no connection. With it, a pipeline of OpenTelemetry of its own sends the traces to Langfuse;
 * it is not the tracing of the whole application (research R-2).
 */
@Configuration
@EnableConfigurationProperties(TracingProperties.class)
public class TracingConfig {

    private static final Logger LOG = LoggerFactory.getLogger(TracingConfig.class);
    private static final String TRACES_PATH = "/v1/traces";
    private static final String INGESTION_VERSION_HEADER = "x-langfuse-ingestion-version";
    private static final String INGESTION_VERSION = "4";

    @Bean
    public ChatTracePort chatTracePort(TracingProperties properties, ObjectProvider<SpanExporter> exporterOverride,
            ObjectProvider<MeterRegistry> meterRegistry,
            @Value("${spring.application.name:helpdesk-backend}") String applicationName) {
        if (!properties.enabled()) {
            return new NoOpChatTrace();
        }
        if (!properties.hasKeys()) {
            LOG.warn("The tracing of the chat is on but the public or the secret key is missing: no trace is sent");
            return new NoOpChatTrace();
        }
        DerivedTraceIdGenerator ids = new DerivedTraceIdGenerator();
        MeterRegistry meters = meterRegistry.getIfAvailable(SimpleMeterRegistry::new);
        SpanExporter exporter = new ExportFailureReporter(
                exporterOverride.getIfAvailable(() -> otlpExporter(properties)), meters);
        SdkMeterProvider droppedMeter = DroppedSpansReporter.meterProvider(meters);
        BatchSpanProcessor processor = BatchSpanProcessor.builder(exporter)
                .setMeterProvider(droppedMeter)
                .setInternalTelemetryVersion(DroppedSpansReporter.telemetryVersion())
                .setMaxQueueSize(properties.queueSize())
                .setMaxExportBatchSize(properties.batchSize())
                .setScheduleDelay(properties.scheduleDelay())
                .setExporterTimeout(properties.exportTimeout())
                .build();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setIdGenerator(ids)
                .setSampler(Sampler.alwaysOn())
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.builder()
                        .put("service.name", applicationName)
                        .put("deployment.environment.name", properties.environment())
                        .build())))
                .addSpanProcessor(processor)
                .build();
        LOG.info("The chat turns are traced to Langfuse: endpoint={}, environment={}", properties.endpoint(),
                properties.environment());
        return new LangfuseChatTrace(provider, ids, properties, droppedMeter::close);
    }

    private static SpanExporter otlpExporter(TracingProperties properties) {
        String credentials = properties.publicKey() + ":" + properties.secretKey();
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
        String base = properties.endpoint().endsWith("/")
                ? properties.endpoint().substring(0, properties.endpoint().length() - 1) : properties.endpoint();
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(base + TRACES_PATH)
                .addHeader("Authorization", authorization)
                .addHeader(INGESTION_VERSION_HEADER, INGESTION_VERSION)
                .setTimeout(properties.exportTimeout())
                .build();
    }
}
