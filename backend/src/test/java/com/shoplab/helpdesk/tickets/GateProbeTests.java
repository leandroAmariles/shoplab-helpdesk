package com.shoplab.helpdesk.tickets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GateProbeTests {

    @Test
    void picksDefaultValue() {
        assertEquals(1, new GateProbe().pick(false));
    }
}
