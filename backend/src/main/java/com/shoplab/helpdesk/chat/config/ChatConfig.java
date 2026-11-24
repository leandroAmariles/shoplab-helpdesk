package com.shoplab.helpdesk.chat.config;

import com.shoplab.helpdesk.chat.api.AssistantSettingsApi;
import com.shoplab.helpdesk.chat.application.port.in.GetAssistantSettingsServicePort;
import com.shoplab.helpdesk.chat.application.usecase.GetAssistantSettingsUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ChatProperties.class, RagProperties.class, MemoryProperties.class,
        GatewayProperties.class, SmallTalkProperties.class, FeedbackProperties.class})
public class ChatConfig {

    @Bean
    @ConditionalOnBean(AssistantSettingsApi.class)
    public GetAssistantSettingsServicePort getAssistantSettingsServicePort(AssistantSettingsApi assistantSettingsApi) {
        return new GetAssistantSettingsUseCase(assistantSettingsApi);
    }
}
