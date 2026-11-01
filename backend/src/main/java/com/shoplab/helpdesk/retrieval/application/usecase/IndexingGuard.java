package com.shoplab.helpdesk.retrieval.application.usecase;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Lets one run of the embeddings step go at a time. There is a single instance of the monolith until F5, so an
 * in-memory flag is enough; the optimistic check when the vectors are stored keeps the data right even without it.
 */
@Component
public class IndexingGuard {

    private final AtomicBoolean running = new AtomicBoolean();

    /** Takes the turn if nobody else has it; never waits. */
    public boolean tryAcquire() {
        return running.compareAndSet(false, true);
    }

    public void release() {
        running.set(false);
    }
}
