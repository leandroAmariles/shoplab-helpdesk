package com.shoplab.helpdesk.shared.config;

import com.shoplab.helpdesk.shared.infrastructure.out.database.ModuleSchema;
import com.shoplab.helpdesk.shared.infrastructure.out.database.ModuleSchemaMigrator;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The database is mandatory: the schemas of all modules are migrated while the context starts, and a failure stops
 * the application. Beans that read or write data must declare {@code @DependsOn("moduleSchemaMigrator")} so they never
 * see a schema that is not ready.
 */
@Configuration
public class DatabaseConfig {

    @Bean(initMethod = "migrate")
    public ModuleSchemaMigrator moduleSchemaMigrator(ObjectProvider<ModuleSchema> schemas, DataSource dataSource) {
        return new ModuleSchemaMigrator(schemas.orderedStream().toList(), dataSource);
    }
}
