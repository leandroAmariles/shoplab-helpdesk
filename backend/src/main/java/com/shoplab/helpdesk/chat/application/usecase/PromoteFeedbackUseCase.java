package com.shoplab.helpdesk.chat.application.usecase;

import com.shoplab.helpdesk.chat.application.port.in.PromoteFeedbackServicePort;
import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.application.port.out.FeedbackStorePort;
import com.shoplab.helpdesk.chat.domain.model.FeedbackItem;
import com.shoplab.helpdesk.chat.domain.model.GoldenCaseDraft;
import com.shoplab.helpdesk.chat.domain.model.PromoteFeedbackCommand;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Validates a golden dataset case draft against the LAB-27 rules and marks the negative feedback turn as promoted in
 * {@code chat.feedback}. Logs carry only the case identifier and category, never customer text or turn identifiers.
 */
@Service
public class PromoteFeedbackUseCase implements PromoteFeedbackServicePort {

    private static final Logger LOG = LoggerFactory.getLogger(PromoteFeedbackUseCase.class);

    private final FeedbackStorePort store;
    private final GoldenCaseValidator validator;
    private final ChatMetricsPort metrics;

    public PromoteFeedbackUseCase(
            FeedbackStorePort store, GoldenCaseValidator validator, ChatMetricsPort metrics) {
        this.store = store;
        this.validator = validator;
        this.metrics = metrics;
    }

    @Override
    public Mono<FeedbackItem> promote(PromoteFeedbackCommand command) {
        return Mono.defer(() -> {
            if (command == null) {
                throw new BusinessException(ResponseCodeEnum.INVALID_FEEDBACK);
            }
            String turnId = SubmitFeedbackUseCase.normalizeTurnId(command.turnId());
            GoldenCaseDraft draft = command.draft();
            String canonicalJsonl = validator.validateAndSerialize(draft);
            String caseId = draft.caseId().strip();
            String category = draft.category().strip();
            return store.promote(turnId, caseId, canonicalJsonl)
                    .doOnSuccess(item -> {
                        metrics.recordFeedbackPromoted();
                        LOG.info("Chat feedback promoted to golden case: caseId={}, category={}", caseId, category);
                    });
        });
    }
}
