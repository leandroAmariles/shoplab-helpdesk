package com.shoplab.helpdesk.tickets.config;

import com.shoplab.helpdesk.shared.infrastructure.out.database.ModuleSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the schema this module owns; the migrator creates it and applies db/migration/tickets. */
@Configuration
public class TicketsSchemaConfig {

    @Bean
    public ModuleSchema ticketsSchema() {
        return new ModuleSchema("tickets");
    }
}
