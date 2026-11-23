package com.shoplab.helpdesk.chat.application.port.in;

import com.shoplab.helpdesk.chat.domain.model.FeedbackListing;
import reactor.core.publisher.Mono;

/** Inbound port to list recorded feedback turns with their turn context and promotion status. */
public interface ListFeedbackServicePort {

    Mono<FeedbackListing> list(ListFeedbackQuery query);
}
