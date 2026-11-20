package com.shoplab.helpdesk.chat.infrastructure.support;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Lets a warning through at most once per interval for each key, so a prompt that stays invalid or a Langfuse that
 * stays down is told once per interval and not once per refresh. Keys are small and few, like
 * {@code prompt:<name>:<reason>}.
 */
public final class KeyedWarnThrottle {

    private final long intervalNanos;
    private final LongSupplier nanoClock;
    private final Map<String, Long> lastNanos = new ConcurrentHashMap<>();

    public KeyedWarnThrottle(Duration interval) {
        this(interval, System::nanoTime);
    }

    /** With the clock given, so a test does not have to wait. */
    public KeyedWarnThrottle(Duration interval, LongSupplier nanoClock) {
        this.intervalNanos = interval.toNanos();
        this.nanoClock = nanoClock;
    }

    /** True for the first call of each interval for this key. */
    public boolean allow(String key) {
        long now = nanoClock.getAsLong();
        boolean[] allowed = {false};
        lastNanos.compute(key, (k, last) -> {
            if (last == null || now - last >= intervalNanos) {
                allowed[0] = true;
                return now;
            }
            return last;
        });
        return allowed[0];
    }
}
