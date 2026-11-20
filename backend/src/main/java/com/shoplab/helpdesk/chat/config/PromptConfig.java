package com.shoplab.helpdesk.chat.config;

import com.shoplab.helpdesk.chat.application.port.out.PromptCatalogPort;
import com.shoplab.helpdesk.chat.application.usecase.PromptValidator;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.CachedPromptCatalog;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.DefaultPromptCatalog;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.LangfusePromptClient;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.PromptDefaults;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.PromptPublisher;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.PromptRefresher;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.RetiredPromptVariablesCheck;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Chooses where the prompts of the chat come from (LAB-85). With the management on and the keys of Langfuse present,
 * a catalog in memory is loaded from Langfuse and refreshed in the background. Otherwise the chat uses the default
 * text of each prompt and no client, thread or connection is created. It does not depend on the tracing: each is
 * switched on by itself.
 */
@Configuration
@EnableConfigurationProperties(PromptProperties.class)
public class PromptConfig {

    private static final Logger LOG = LoggerFactory.getLogger(PromptConfig.class);

    @Bean
    public PromptValidator promptValidator(PromptProperties properties) {
        return new PromptValidator(properties.maxPromptCharacters());
    }

    @Bean
    public PromptDefaults promptDefaults(PromptValidator validator) {
        return new PromptDefaults(validator);
    }

    /** Warns once, at start-up, about the variables whose prompt text moved to Langfuse. */
    @Bean
    public RetiredPromptVariablesCheck retiredPromptVariablesCheck(Environment environment) {
        RetiredPromptVariablesCheck check = new RetiredPromptVariablesCheck(environment);
        check.warn();
        return check;
    }

    /** The management is off, or Langfuse cannot be authenticated: always the default text. */
    @Configuration
    @Conditional(PromptsInactiveCondition.class)
    static class DefaultPrompts {

        @Bean
        PromptCatalogPort promptCatalog(PromptDefaults defaults, PromptProperties properties) {
            if (properties.enabled()) {
                LOG.warn("The prompts are managed in Langfuse but its public or its secret key is missing: the "
                        + "default texts are used");
            }
            return new DefaultPromptCatalog(defaults);
        }
    }

    /** The prompts are loaded from Langfuse, kept in memory and refreshed in the background. */
    @Configuration
    @Conditional(PromptsActiveCondition.class)
    static class ManagedPrompts {

        @Bean
        LangfusePromptClient langfusePromptClient(WebClient.Builder builder, PromptProperties properties) {
            return new LangfusePromptClient(builder.clone(), properties);
        }

        @Bean
        CachedPromptCatalog promptCatalog(LangfusePromptClient client, PromptValidator validator,
                PromptDefaults defaults, PromptProperties properties, ObjectProvider<MeterRegistry> meters) {
            return new CachedPromptCatalog(client, validator, defaults, properties.label(),
                    meters.getIfAvailable(SimpleMeterRegistry::new));
        }

        @Bean
        PromptPublisher promptPublisher(LangfusePromptClient client, PromptDefaults defaults,
                PromptProperties properties) {
            return new PromptPublisher(client, defaults, properties.label());
        }

        @Bean
        PromptRefresher promptRefresher(CachedPromptCatalog catalog, PromptPublisher publisher,
                PromptProperties properties) {
            return new PromptRefresher(catalog, publisher, properties.refreshInterval(), properties.requestTimeout(),
                    properties.publishMissing());
        }
    }

    /** The management is on and Langfuse can be authenticated. */
    static class PromptsActiveCondition implements org.springframework.context.annotation.Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return properties(context).active();
        }
    }

    /** The opposite of {@link PromptsActiveCondition}. */
    static class PromptsInactiveCondition implements org.springframework.context.annotation.Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !properties(context).active();
        }
    }

    private static PromptProperties properties(ConditionContext context) {
        return Binder.get(context.getEnvironment()).bindOrCreate("shoplab.chat.prompts", PromptProperties.class);
    }
}
