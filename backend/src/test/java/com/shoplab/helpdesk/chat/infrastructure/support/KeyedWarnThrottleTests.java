package com.shoplab.helpdesk.chat.infrastructure.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class KeyedWarnThrottleTests {

    private final AtomicLong now = new AtomicLong(1_000_000_000_000L);
    private final KeyedWarnThrottle throttle = new KeyedWarnThrottle(Duration.ofMinutes(1), now::get);

    @Test
    void theFirstWarningOfAKeyPassesAndTheRestOfTheIntervalDoNot() {
        assertThat(throttle.allow("prompt:chat-system:blank")).isTrue();
        now.addAndGet(Duration.ofSeconds(59).toNanos());
        assertThat(throttle.allow("prompt:chat-system:blank")).isFalse();
    }

    @Test
    void aDifferentKeyHasItsOwnInterval() {
        assertThat(throttle.allow("prompt:chat-system:blank")).isTrue();
        assertThat(throttle.allow("prompt:chat-context:blank")).isTrue();
        assertThat(throttle.allow("prompt:chat-system:too-long")).isTrue();
    }

    @Test
    void aWarningPassesAgainWhenTheIntervalHasElapsed() {
        assertThat(throttle.allow("k")).isTrue();
        now.addAndGet(Duration.ofMinutes(1).toNanos());
        assertThat(throttle.allow("k")).isTrue();
        assertThat(throttle.allow("k")).isFalse();
    }

    @Test
    void theClockOfTheSystemIsTheDefault() {
        KeyedWarnThrottle real = new KeyedWarnThrottle(Duration.ofHours(1));

        assertThat(real.allow("k")).isTrue();
        assertThat(real.allow("k")).isFalse();
    }
}
