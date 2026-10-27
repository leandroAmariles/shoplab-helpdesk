package com.shoplab.helpdesk.shared.infrastructure.out.database;

import java.util.regex.Pattern;

/**
 * The data a business module owns: a PostgreSQL schema named like the module, migrated from
 * {@code db/migration/<name>} (specs/LAB-105, FR-015). Each module declares one as a bean.
 *
 * <p>An invalid name is a wiring mistake found at start-up, not a business or infrastructure error, so it is an
 * {@link IllegalArgumentException} that never reaches HTTP.
 */
public record ModuleSchema(String name) {

    private static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final String SHARED_SCHEMA = "public";

    public ModuleSchema {
        if (name == null || !VALID_NAME.matcher(name).matches() || SHARED_SCHEMA.equals(name)) {
            throw new IllegalArgumentException("Not a valid module schema name: '" + name + "'");
        }
    }

    public String location() {
        return "classpath:db/migration/" + name;
    }
}
