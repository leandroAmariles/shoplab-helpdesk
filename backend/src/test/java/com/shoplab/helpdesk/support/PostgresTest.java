package com.shoplab.helpdesk.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.ContextConfiguration;

/** Marks a test that starts the application: the database is mandatory, so it runs against {@link SharedPostgres}. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ContextConfiguration(initializers = SharedPostgres.Initializer.class)
public @interface PostgresTest {
}
