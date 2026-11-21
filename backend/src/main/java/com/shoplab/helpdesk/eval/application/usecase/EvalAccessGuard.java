package com.shoplab.helpdesk.eval.application.usecase;

import com.shoplab.helpdesk.eval.config.EvalProperties;
import com.shoplab.helpdesk.eval.domain.exception.EvalPersistenceDisabledException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/**
 * Lets a request through only when saving is on (a token is configured) and the request carries that token. The backend
 * has no authentication yet, so a write is never open (research.md R7). The comparison takes the same time whatever
 * the part that matches, and neither token is ever written to a message or a log.
 */
@Component
public class EvalAccessGuard {

    private final byte[] configured;

    public EvalAccessGuard(EvalProperties properties) {
        this.configured = properties.token().getBytes(StandardCharsets.UTF_8);
    }

    /** @throws EvalPersistenceDisabledException when saving is off or the token is missing or different */
    public void check(String presented) {
        if (configured.length == 0 || presented == null
                || !MessageDigest.isEqual(configured, presented.getBytes(StandardCharsets.UTF_8))) {
            throw new EvalPersistenceDisabledException();
        }
    }
}
