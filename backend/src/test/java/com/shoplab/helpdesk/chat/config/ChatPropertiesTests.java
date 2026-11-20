package com.shoplab.helpdesk.chat.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ChatPropertiesTests {

    @Test
    void theMessageLimitDefaultsToFourThousand() {
        assertThat(new ChatProperties(null).maxMessageLength()).isEqualTo(4000);
    }

    @Test
    void anExplicitMessageLimitIsKept() {
        assertThat(new ChatProperties(500).maxMessageLength()).isEqualTo(500);
    }

    /**
     * The text of the prompts moved to Langfuse and to {@code prompts/*.txt} (LAB-85): application.yml no longer
     * carries any of them, so the text has one copy only.
     */
    @Test
    void applicationYmlNoLongerCarriesTheTextOfAnyPrompt() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        PropertySource<?> yml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).get(0);
        environment.getPropertySources().addFirst(yml);

        assertThat(environment.containsProperty("shoplab.chat.system-prompt")).isFalse();
        assertThat(environment.containsProperty("shoplab.chat.small-talk.system-prompt")).isFalse();
        assertThat(environment.containsProperty("shoplab.chat.rag.context-template")).isFalse();
        assertThat(environment.containsProperty("shoplab.chat.rag.empty-context-template")).isFalse();
        assertThat(Binder.get(environment).bind("shoplab.chat", ChatProperties.class).get().maxMessageLength())
                .isEqualTo(4000);
    }

    @Test
    void theManagementOfPromptsIsOffByDefaultInApplicationYml() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        PropertySource<?> yml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).get(0);
        environment.getPropertySources().addFirst(yml);

        PromptProperties prompts = Binder.get(environment).bind("shoplab.chat.prompts", PromptProperties.class).get();

        assertThat(prompts.enabled()).isFalse();
        assertThat(prompts.publishMissing()).isFalse();
        assertThat(prompts.overrideEnabled()).isFalse();
        assertThat(prompts.label()).isEqualTo("production");
    }
}
