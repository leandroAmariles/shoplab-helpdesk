package com.shoplab.helpdesk.chat.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class StubKnowledgeContextConfig {

    @Bean
    @Primary
    StubKnowledgeContext stubKnowledgeContext() {
        return new StubKnowledgeContext();
    }
}
