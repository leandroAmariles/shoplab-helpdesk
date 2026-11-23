package com.shoplab.helpdesk.chat.application.usecase;

import com.shoplab.helpdesk.chat.application.port.in.SubmitFeedbackServicePort;
import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.application.port.out.FeedbackStorePort;
import com.shoplab.helpdesk.chat.domain.model.FeedbackRecord;
import com.shoplab.helpdesk.chat.domain.model.SubmitFeedbackCommand;
import com.shoplab.helpdesk.chat.domain.model.constants.FeedbackConstants;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Validates and persists a customer rating ({@code UP} or {@code DOWN}) and optional comment for a completed chat turn.
 * Logs carry only the rating and whether a comment was provided, never the comment text or turn identifier.
 */
@Service
public class SubmitFeedbackUseCase implements SubmitFeedbackServicePort {

    private static final Logger LOG = LoggerFactory.getLogger(SubmitFeedbackUseCase.class);

    private final FeedbackStorePort store;
    private final ChatMetricsPort metrics;

    public SubmitFeedbackUseCase(FeedbackStorePort store, ChatMetricsPort metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    @Override
    public Mono<FeedbackRecord> submit(SubmitFeedbackCommand command) {
        return Mono.defer(() -> {
            SubmitFeedbackCommand normalized = validateAndNormalize(command);
            return store.upsert(normalized)
                    .doOnSuccess(record -> {
                        metrics.recordFeedback(normalized.rating());
                        LOG.info("Chat feedback recorded: rating={}, hasComment={}",
                                normalized.rating().tagValue(), normalized.comment() != null);
                    });
        });
    }

    static String normalizeTurnId(String rawTurnId) {
        if (rawTurnId == null || rawTurnId.isBlank()) {
            throw new BusinessException(ResponseCodeEnum.INVALID_FEEDBACK);
        }
        String candidate = rawTurnId.strip().toLowerCase(Locale.ROOT);
        try {
            UUID parsed = UUID.fromString(candidate);
            if (!parsed.toString().equals(candidate)) {
                throw new BusinessException(ResponseCodeEnum.INVALID_FEEDBACK);
            }
            return candidate;
        } catch (IllegalArgumentException error) {
            throw new BusinessException(ResponseCodeEnum.INVALID_FEEDBACK);
        }
    }

    private static SubmitFeedbackCommand validateAndNormalize(SubmitFeedbackCommand command) {
        if (command == null || command.rating() == null) {
            throw new BusinessException(ResponseCodeEnum.INVALID_FEEDBACK);
        }
        String turnId = normalizeTurnId(command.turnId());
        String comment = normalizeComment(command.comment());
        return new SubmitFeedbackCommand(turnId, command.rating(), comment);
    }

    private static String normalizeComment(String rawComment) {
        if (rawComment == null) {
            return null;
        }
        String stripped = rawComment.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.length() > FeedbackConstants.MAX_COMMENT_LENGTH) {
            throw new BusinessException(ResponseCodeEnum.INVALID_FEEDBACK);
        }
        return stripped;
    }
}
