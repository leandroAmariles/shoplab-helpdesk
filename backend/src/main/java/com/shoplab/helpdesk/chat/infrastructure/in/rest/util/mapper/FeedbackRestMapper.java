package com.shoplab.helpdesk.chat.infrastructure.in.rest.util.mapper;

import com.shoplab.helpdesk.chat.application.port.in.ListFeedbackQuery;
import com.shoplab.helpdesk.chat.domain.model.FeedbackItem;
import com.shoplab.helpdesk.chat.domain.model.FeedbackListing;
import com.shoplab.helpdesk.chat.domain.model.FeedbackRecord;
import com.shoplab.helpdesk.chat.domain.model.GoldenCaseDraft;
import com.shoplab.helpdesk.chat.domain.model.PromoteFeedbackCommand;
import com.shoplab.helpdesk.chat.domain.model.SubmitFeedbackCommand;
import com.shoplab.helpdesk.chat.domain.model.constants.FeedbackConstants;
import com.shoplab.helpdesk.chat.domain.model.enums.FeedbackRating;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.FeedbackItemResponse;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.FeedbackListResponse;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.FeedbackResponse;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.PromoteFeedbackRequest;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.SubmitFeedbackRequest;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;

/** Explicit mapper between REST DTOs and domain models for answer feedback and golden-case promotion. */
public final class FeedbackRestMapper {

    private FeedbackRestMapper() {
    }

    public static SubmitFeedbackCommand toSubmitCommand(String turnId, SubmitFeedbackRequest request) {
        if (request == null) {
            throw new BusinessException(ResponseCodeEnum.INVALID_FEEDBACK);
        }
        return new SubmitFeedbackCommand(turnId, FeedbackRating.fromValue(request.rating()), request.comment());
    }

    public static ListFeedbackQuery toListQuery(String rating, Boolean promoted, Integer limit) {
        FeedbackRating parsedRating = rating == null || rating.isBlank()
                ? FeedbackRating.DOWN
                : FeedbackRating.fromValue(rating);
        int effectiveLimit = limit == null ? FeedbackConstants.DEFAULT_LIST_LIMIT : limit;
        return new ListFeedbackQuery(parsedRating, promoted, effectiveLimit);
    }

    public static PromoteFeedbackCommand toPromoteCommand(String turnId, PromoteFeedbackRequest request) {
        if (request == null) {
            throw new BusinessException(ResponseCodeEnum.INVALID_FEEDBACK);
        }
        GoldenCaseDraft draft = new GoldenCaseDraft(
                request.caseId(),
                request.question(),
                request.referenceAnswer(),
                request.expectedDocuments(),
                request.category());
        return new PromoteFeedbackCommand(turnId, draft);
    }

    public static FeedbackResponse toResponse(FeedbackRecord record) {
        return new FeedbackResponse(
                record.turnId(),
                record.rating().name(),
                record.comment(),
                record.createdAt(),
                record.updatedAt(),
                record.promotedCaseId(),
                record.promotedAt());
    }

    public static FeedbackItemResponse toItemResponse(FeedbackItem item) {
        return new FeedbackItemResponse(
                item.turnId(),
                item.conversationId(),
                item.question(),
                item.answer(),
                item.deliveredDocuments(),
                item.traceId(),
                item.rating().name(),
                item.comment(),
                item.createdAt(),
                item.updatedAt(),
                item.promotedCaseId(),
                item.promotedCaseJsonl(),
                item.promotedAt());
    }

    public static FeedbackListResponse toListResponse(FeedbackListing listing) {
        return new FeedbackListResponse(
                listing.total(),
                listing.pendingCount(),
                listing.promotedCount(),
                listing.suggestedNextCaseId(),
                listing.items().stream().map(FeedbackRestMapper::toItemResponse).toList());
    }
}
