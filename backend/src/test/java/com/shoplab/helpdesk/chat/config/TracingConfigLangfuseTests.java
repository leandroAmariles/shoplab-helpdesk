package com.shoplab.helpdesk.chat.config;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.shoplab.helpdesk.chat.application.port.out.ChatTracePort;
import com.shoplab.helpdesk.chat.infrastructure.out.tracing.LangfuseChatTrace;
import com.shoplab.helpdesk.chat.infrastructure.out.tracing.NoOpChatTrace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A bad or missing setting of the tracing never stops the application, and the switch really decides whether anything
 * is created or any connection is opened (specs/LAB-64, US4, FR-008 and FR-022).
 */
@ExtendWith(OutputCaptureExtension.class)
class TracingConfigLangfuseTests {

    private static final String PUBLIC_KEY = "pk-lf-only-public";
    private static final String SECRET_KEY = "sk-lf-only-secret";

    @RegisterExtension
    static WireMockExtension control = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort().gzipDisabled(true)).build();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TracingConfig.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void offCreatesNoTracerNoExporterAndOpensNoConnection() throws InterruptedException {
        runner.withPropertyValues("shoplab.chat.tracing.endpoint=" + control.baseUrl() + "/api/public/otel",
                "shoplab.chat.tracing.public-key=" + PUBLIC_KEY, "shoplab.chat.tracing.secret-key=" + SECRET_KEY)
                .run(context -> {
                    assertThat(context.getBean(ChatTracePort.class)).isInstanceOf(NoOpChatTrace.class);
                    assertThat(context).doesNotHaveBean(SdkTracerProvider.class);
                    assertThat(context).doesNotHaveBean(SpanExporter.class);
                });
        Thread.sleep(300);

        control.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void onWithoutKeysFallsBackToTheNoOpAndTheWarningHasNoKeyValues(CapturedOutput output) {
        runner.withPropertyValues("shoplab.chat.tracing.enabled=true",
                "shoplab.chat.tracing.public-key=" + PUBLIC_KEY).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatTracePort.class)).isInstanceOf(NoOpChatTrace.class);
                });

        assertThat(output.getAll()).contains("key is missing").doesNotContain(PUBLIC_KEY, SECRET_KEY);
    }

    @Test
    void onWithKeysBuildsTheLangfuseTraceAndClosingTheContextDoesNotThrow() {
        runner.withPropertyValues("shoplab.chat.tracing.enabled=true",
                "shoplab.chat.tracing.endpoint=" + control.baseUrl() + "/api/public/otel",
                "shoplab.chat.tracing.public-key=" + PUBLIC_KEY, "shoplab.chat.tracing.secret-key=" + SECRET_KEY)
                .run(context -> {
                    assertThat(context.getBean(ChatTracePort.class)).isInstanceOf(LangfuseChatTrace.class);
                    assertThatCode(() -> closeOf(context)).doesNotThrowAnyException();
                });
    }

    private static void closeOf(AssertableApplicationContext context) {
        context.close();
    }
}
