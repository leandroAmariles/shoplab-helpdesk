package com.shoplab.helpdesk.shared.infrastructure.out.database;

import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ModuleMigrationException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the migrations of every module, one Flyway per module, each in its own schema with its own history table.
 * Running them separately makes it impossible for a script of one module to land in another's schema, and lets a
 * module leave the monolith together with its data (specs/LAB-105, research D-2).
 *
 * <p>Runs at start-up on the main thread. Any failure stops the application with a {@link ModuleMigrationException}
 * that names the module; nothing is left half started.
 */
public class ModuleSchemaMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleSchemaMigrator.class);
    private static final String CONNECTION_EXCEPTION_SQL_STATE = "08";

    private final List<ModuleSchema> schemas;
    private final DataSource dataSource;

    public ModuleSchemaMigrator(List<ModuleSchema> schemas, DataSource dataSource) {
        this.schemas = List.copyOf(schemas);
        this.dataSource = dataSource;
    }

    /** The modules in the order they are migrated; they must not depend on each other's data, so any order works. */
    List<ModuleSchema> orderedSchemas() {
        Set<String> seen = new HashSet<>();
        for (ModuleSchema schema : schemas) {
            if (!seen.add(schema.name())) {
                throw new IllegalArgumentException("Two modules declare the schema '" + schema.name() + "'");
            }
        }
        return schemas.stream().sorted(Comparator.comparing(ModuleSchema::name)).toList();
    }

    public void migrate() {
        for (ModuleSchema schema : orderedSchemas()) {
            migrate(schema);
        }
    }

    private void migrate(ModuleSchema schema) {
        try {
            MigrateResult result = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema.name())
                    .defaultSchema(schema.name())
                    .createSchemas(true)
                    .locations(schema.location())
                    .load()
                    .migrate();
            LOG.info("Schema '{}' ready: {} migration(s) applied", schema.name(), result.migrationsExecuted);
        } catch (RuntimeException failure) {
            ResponseCodeEnum code = isConnectionFailure(failure)
                    ? ResponseCodeEnum.DATABASE_UNAVAILABLE
                    : ResponseCodeEnum.DATABASE_MIGRATION_FAILED;
            throw ExceptionUtil.moduleMigration(schema.name(), code, failure);
        }
    }

    private static boolean isConnectionFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof SocketTimeoutException
                    || cause instanceof UnknownHostException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith(CONNECTION_EXCEPTION_SQL_STATE)) {
                return true;
            }
        }
        return false;
    }
}
