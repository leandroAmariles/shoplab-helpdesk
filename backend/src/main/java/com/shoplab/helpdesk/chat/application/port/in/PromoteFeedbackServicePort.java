package com.shoplab.helpdesk.chat.application.port.in;

import com.shoplab.helpdesk.chat.domain.model.FeedbackItem;
import com.shoplab.helpdesk.chat.domain.model.PromoteFeedbackCommand;
import reactor.core.publisher.Mono;

/** Inbound port to validate and promote a negative feedback turn to a golden dataset case. */
public interface PromoteFeedbackServicePort {

    Mono<FeedbackItem> promote(PromoteFeedbackCommand command);
}
