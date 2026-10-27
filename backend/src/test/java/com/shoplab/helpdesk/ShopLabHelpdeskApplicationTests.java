package com.shoplab.helpdesk;

import com.shoplab.helpdesk.support.PostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@PostgresTest
class ShopLabHelpdeskApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the context starts without a database or a model server.
    }
}
