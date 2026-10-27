package com.shoplab.helpdesk.orders.config;

import com.shoplab.helpdesk.shared.infrastructure.out.database.ModuleSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the schema this module owns; the migrator creates it and applies db/migration/orders. */
@Configuration
public class OrdersSchemaConfig {

    @Bean
    public ModuleSchema ordersSchema() {
        return new ModuleSchema("orders");
    }
}
