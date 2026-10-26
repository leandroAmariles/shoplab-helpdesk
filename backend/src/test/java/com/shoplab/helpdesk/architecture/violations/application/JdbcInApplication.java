package com.shoplab.helpdesk.architecture.violations.application;

import javax.sql.DataSource;

/** Fixture: a use case that depends on JDBC (violates A2). */
public class JdbcInApplication {

    private final DataSource dataSource;

    public JdbcInApplication(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DataSource dataSource() {
        return dataSource;
    }
}
