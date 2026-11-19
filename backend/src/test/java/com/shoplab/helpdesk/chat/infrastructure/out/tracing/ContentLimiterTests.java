package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.model.constants.TracingConstants;
import org.junit.jupiter.api.Test;

class ContentLimiterTests {

    private final ContentLimiter limiter = new ContentLimiter(10);

    @Test
    void aTextWithinTheLimitIsReturnedAsItIs() {
        ContentLimiter.Limited limited = limiter.limit("0123456789");

        assertThat(limited.text()).isEqualTo("0123456789");
        assertThat(limited.truncated()).isFalse();
    }

    @Test
    void aLongerTextIsCutAtTheLimitAndMarked() {
        ContentLimiter.Limited limited = limiter.limit("0123456789ABC");

        assertThat(limited.text()).isEqualTo("0123456789" + TracingConstants.TRUNCATED_SUFFIX);
        assertThat(limited.truncated()).isTrue();
    }

    @Test
    void oneCharacterOverTheLimitIsAlreadyTruncated() {
        assertThat(limiter.limit("0123456789A").truncated()).isTrue();
    }

    @Test
    void nullBecomesEmptyAndIsNotTruncated() {
        ContentLimiter.Limited limited = limiter.limit(null);

        assertThat(limited.text()).isEmpty();
        assertThat(limited.truncated()).isFalse();
    }

    @Test
    void aSurrogatePairIsNeverSplit() {
        // 9 ASCII characters followed by an emoji (2 chars): the cut at 10 would split the pair.
        ContentLimiter.Limited limited = limiter.limit("012345678" + "😀" + "tail");

        assertThat(limited.text()).isEqualTo("012345678" + TracingConstants.TRUNCATED_SUFFIX);
        assertThat(limited.truncated()).isTrue();
    }
}
