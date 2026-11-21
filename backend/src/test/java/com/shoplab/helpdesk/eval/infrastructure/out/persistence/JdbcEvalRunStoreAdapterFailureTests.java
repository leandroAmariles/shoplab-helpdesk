package com.shoplab.helpdesk.eval.infrastructure.out.persistence;

import static com.shoplab.helpdesk.eval.EvalRunFixtures.hashed;

import com.shoplab.helpdesk.eval.EvalRunFixtures;
import com.shoplab.helpdesk.eval.domain.exception.EvalStoreUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.test.StepVerifier;

/** With the database down nothing is saved and the failure is the typed one, whatever the driver says. */
class JdbcEvalRunStoreAdapterFailureTests {

    private final DriverManagerDataSource unreachable = new DriverManagerDataSource(
            "jdbc:postgresql://127.0.0.1:1/none", "nobody", "nothing");
    private final JdbcEvalRunStoreAdapter adapter = new JdbcEvalRunStoreAdapter(new JdbcTemplate(unreachable),
            new TransactionTemplate(new DataSourceTransactionManager(unreachable)));

    @Test
    void savingWithTheDatabaseDownIsTheStoreUnavailable() {
        StepVerifier.create(adapter.save(hashed(EvalRunFixtures.quality("down-1", 8).build())))
                .expectError(EvalStoreUnavailableException.class).verify();
    }

    @Test
    void lookingForARunWithTheDatabaseDownIsTheStoreUnavailable() {
        StepVerifier.create(adapter.find("down-1")).expectError(EvalStoreUnavailableException.class).verify();
    }
}
