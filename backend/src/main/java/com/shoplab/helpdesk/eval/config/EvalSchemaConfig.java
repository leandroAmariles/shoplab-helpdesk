package com.shoplab.helpdesk.eval.config;

import com.shoplab.helpdesk.shared.infrastructure.out.database.ModuleSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the schema this module owns; the migrator creates it and applies db/migration/eval. */
@Configuration
public class EvalSchemaConfig {

    @Bean
    public ModuleSchema evalSchema() {
        return new ModuleSchema("eval");
    }
}
