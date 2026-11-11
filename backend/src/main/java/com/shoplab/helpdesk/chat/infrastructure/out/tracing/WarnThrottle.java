package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/** Lets a warning through at most once per interval, so a Langfuse that is down does not flood the log. */
final class WarnThrottle {

    private final long intervalNanos;
    private final AtomicLong lastNanos;

    WarnThrottle(Duration interval) {
        this.intervalNanos = interval.toNanos();
        this.lastNanos = new AtomicLong(System.nanoTime() - intervalNanos);
    }

    /** True for the first call of each interval. */
    boolean allow() {
        long now = System.nanoTime();
        long last = lastNanos.get();
        return now - last >= intervalNanos && lastNanos.compareAndSet(last, now);
    }
}
