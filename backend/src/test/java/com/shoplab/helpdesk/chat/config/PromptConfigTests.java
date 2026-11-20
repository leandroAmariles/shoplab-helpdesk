package com.shoplab.helpdesk.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.application.port.out.PromptCatalogPort;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.CachedPromptCatalog;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.DefaultPromptCatalog;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.LangfusePromptClient;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.PromptPublisher;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.PromptRefresher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;

/** What is created depends on the switch of the prompts and on the keys, and on nothing else (FR-011). */
class PromptConfigTests {

    private static final String PREFIX = "shoplab.chat.prompts.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> { })
            .withConfiguration(UserConfigurations.of(PromptConfig.class))
            .withBean(WebClient.Builder.class, WebClient::builder);

    @Test
    void withoutConfigurationThereIsOnlyTheDefaultCatalogAndNoClientNorRefresher() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PromptCatalogPort.class)).isInstanceOf(DefaultPromptCatalog.class);
            assertThat(context).doesNotHaveBean(LangfusePromptClient.class)
                    .doesNotHaveBean(PromptRefresher.class)
                    .doesNotHaveBean(PromptPublisher.class);
        });
    }

    @Test
    void enabledWithoutKeysWarnsAndBehavesAsOff() {
        runner.withPropertyValues(PREFIX + "enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PromptCatalogPort.class)).isInstanceOf(DefaultPromptCatalog.class);
            assertThat(context).doesNotHaveBean(PromptRefresher.class);
        });
    }

    @Test
    void keysWithoutTheSwitchStayOff() {
        runner.withPropertyValues(PREFIX + "public-key=pk", PREFIX + "secret-key=sk").run(context ->
                assertThat(context.getBean(PromptCatalogPort.class)).isInstanceOf(DefaultPromptCatalog.class));
    }

    @Test
    void enabledWithKeysLoadsFromLangfuseWithACachedCatalogAndARefresher() {
        runner.withPropertyValues(PREFIX + "enabled=true", PREFIX + "public-key=pk", PREFIX + "secret-key=sk",
                PREFIX + "base-url=http://localhost:1").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PromptCatalogPort.class)).isInstanceOf(CachedPromptCatalog.class);
                    assertThat(context).hasSingleBean(LangfusePromptClient.class)
                            .hasSingleBean(PromptRefresher.class)
                            .hasSingleBean(PromptPublisher.class);
                });
    }

    @Test
    void thePromptsDoNotDependOnTheTracing() {
        runner.withPropertyValues(PREFIX + "enabled=true", PREFIX + "public-key=pk", PREFIX + "secret-key=sk",
                "shoplab.chat.tracing.enabled=false").run(context ->
                assertThat(context.getBean(PromptCatalogPort.class)).isInstanceOf(CachedPromptCatalog.class));
        runner.withPropertyValues("shoplab.chat.tracing.enabled=true").run(context ->
                assertThat(context.getBean(PromptCatalogPort.class)).isInstanceOf(DefaultPromptCatalog.class));
    }

    @Test
    void anInvalidValueStopsTheStartUpNamingTheProperty() {
        runner.withPropertyValues(PREFIX + "enabled=true", PREFIX + "public-key=pk", PREFIX + "secret-key=sk",
                PREFIX + "refresh-interval=1s").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("refresh-interval");
                });
    }
}
