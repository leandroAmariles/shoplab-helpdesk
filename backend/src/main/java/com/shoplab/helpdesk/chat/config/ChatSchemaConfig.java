package com.shoplab.helpdesk.chat.config;

import com.shoplab.helpdesk.shared.infrastructure.out.database.ModuleSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the schema this module owns; the migrator creates it and applies db/migration/chat. */
@Configuration
public class ChatSchemaConfig {

    @Bean
    public ModuleSchema chatSchema() {
        return new ModuleSchema("chat");
    }
}
