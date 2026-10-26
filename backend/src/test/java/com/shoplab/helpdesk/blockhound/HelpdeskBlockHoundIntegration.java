package com.shoplab.helpdesk.blockhound;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * Allowances of the blocking-call detector: each one names the exact class and method and says why it is accepted.
 * Never allow a whole package or a wildcard (specs/LAB-111, research R6).
 */
public class HelpdeskBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        // No allowances yet: they are added only when the suite shows a justified false positive.
    }
}
