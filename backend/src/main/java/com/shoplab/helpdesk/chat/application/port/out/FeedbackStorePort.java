package com.shoplab.helpdesk.chat.application.port.out;

import com.shoplab.helpdesk.chat.application.port.in.ListFeedbackQuery;
import com.shoplab.helpdesk.chat.domain.model.FeedbackItem;
import com.shoplab.helpdesk.chat.domain.model.FeedbackListing;
import com.shoplab.helpdesk.chat.domain.model.FeedbackRecord;
import com.shoplab.helpdesk.chat.domain.model.SubmitFeedbackCommand;
import reactor.core.publisher.Mono;

/** Outbound persistence port for {@code chat.feedback}. */
public interface FeedbackStorePort {

    /** Inserts or updates the feedback for the given turn. */
    Mono<FeedbackRecord> upsert(SubmitFeedbackCommand command);

    /** Lists feedback joined with turn metadata, summary counts and suggested next case id. */
    Mono<FeedbackListing> list(ListFeedbackQuery query, int baseGoldenCaseNumber);

    /** Marks a negative feedback row as promoted with its validated golden case id and canonical JSONL line. */
    Mono<FeedbackItem> promote(String turnId, String caseId, String canonicalJsonl);
}
