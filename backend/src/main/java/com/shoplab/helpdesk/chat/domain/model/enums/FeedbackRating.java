package com.shoplab.helpdesk.chat.domain.model.enums;

import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.util.Locale;

/** Customer rating for an assistant answer: helpful ({@code UP}) or not helpful ({@code DOWN}). */
public enum FeedbackRating {
    UP,
    DOWN;

    public String tagValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static FeedbackRating fromValue(String raw) {
        if (raw == null) {
            throw new BusinessException(ResponseCodeEnum.INVALID_FEEDBACK);
        }
        String normalized = raw.strip().toUpperCase(Locale.ROOT);
        if ("UP".equals(normalized)) {
            return UP;
        }
        if ("DOWN".equals(normalized)) {
            return DOWN;
        }
        throw new BusinessException(ResponseCodeEnum.INVALID_FEEDBACK);
    }
}
