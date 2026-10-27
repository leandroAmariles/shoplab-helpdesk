package com.shoplab.helpdesk.catalog.config;

import com.shoplab.helpdesk.shared.infrastructure.out.database.ModuleSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the schema this module owns; the migrator creates it and applies db/migration/catalog. */
@Configuration
public class CatalogSchemaConfig {

    @Bean
    public ModuleSchema catalogSchema() {
        return new ModuleSchema("catalog");
    }
}
