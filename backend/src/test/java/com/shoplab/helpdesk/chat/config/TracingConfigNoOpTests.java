package com.shoplab.helpdesk.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.application.port.out.ChatTracePort;
import com.shoplab.helpdesk.chat.infrastructure.out.tracing.NoOpChatTrace;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TracingConfigNoOpTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TracingConfig.class);

    @Test
    void withoutTheSettingTheTracingIsOffAndNothingFromOpenTelemetryExists() {
        runner.run(context -> {
            assertThat(context.getBean(ChatTracePort.class)).isInstanceOf(NoOpChatTrace.class);
            assertThat(context).doesNotHaveBean(SdkTracerProvider.class);
            assertThat(context).doesNotHaveBean(SpanExporter.class);
        });
    }

    @Test
    void explicitlyOffIsTheSame() {
        runner.withPropertyValues("shoplab.chat.tracing.enabled=false").run(context -> {
            assertThat(context.getBean(ChatTracePort.class)).isInstanceOf(NoOpChatTrace.class);
            assertThat(context).doesNotHaveBean(SdkTracerProvider.class);
        });
    }
}
