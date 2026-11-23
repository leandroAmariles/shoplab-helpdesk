package com.shoplab.helpdesk.chat.config;

import com.shoplab.helpdesk.chat.domain.model.constants.FeedbackConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for answer feedback and golden-case promotion under {@code shoplab.chat.feedback}.
 *
 * @param baseGoldenCaseNumber highest case number in the base {@code eval/golden.jsonl} file (default 120), from which
 *                             the next free {@code G<NNN>} identifier is suggested
 */
@ConfigurationProperties("shoplab.chat.feedback")
public record FeedbackProperties(Integer baseGoldenCaseNumber) {

    private static final int MAX_BASE_NUMBER = 998;

    public FeedbackProperties {
        baseGoldenCaseNumber = baseGoldenCaseNumber == null
                ? FeedbackConstants.DEFAULT_BASE_GOLDEN_CASE_NUMBER
                : baseGoldenCaseNumber;
        if (baseGoldenCaseNumber < 0 || baseGoldenCaseNumber > MAX_BASE_NUMBER) {
            throw new IllegalArgumentException(
                    "shoplab.chat.feedback.base-golden-case-number must be between 0 and "
                            + MAX_BASE_NUMBER + " but was " + baseGoldenCaseNumber);
        }
    }
}
