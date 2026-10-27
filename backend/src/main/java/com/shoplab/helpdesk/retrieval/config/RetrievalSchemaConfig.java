package com.shoplab.helpdesk.retrieval.config;

import com.shoplab.helpdesk.shared.infrastructure.out.database.ModuleSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the schema this module owns; the migrator creates it and applies db/migration/retrieval. */
@Configuration
public class RetrievalSchemaConfig {

    @Bean
    public ModuleSchema retrievalSchema() {
        return new ModuleSchema("retrieval");
    }
}
