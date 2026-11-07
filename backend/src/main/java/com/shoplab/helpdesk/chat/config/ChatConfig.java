package com.shoplab.helpdesk.chat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ChatProperties.class, RagProperties.class, MemoryProperties.class})
public class ChatConfig {
}
