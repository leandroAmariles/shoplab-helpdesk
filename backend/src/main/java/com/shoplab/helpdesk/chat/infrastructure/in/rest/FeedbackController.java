package com.shoplab.helpdesk.chat.infrastructure.in.rest;

import com.shoplab.helpdesk.chat.application.port.in.ListFeedbackServicePort;
import com.shoplab.helpdesk.chat.application.port.in.PromoteFeedbackServicePort;
import com.shoplab.helpdesk.chat.application.port.in.SubmitFeedbackServicePort;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.FeedbackItemResponse;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.FeedbackListResponse;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.FeedbackResponse;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.PromoteFeedbackRequest;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.SubmitFeedbackRequest;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.util.mapper.FeedbackRestMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** REST controller for recording answer feedback and promoting negative feedback turns to golden dataset cases. */
@RestController
@RequestMapping("/api/chat/feedback")
public class FeedbackController {

    private final SubmitFeedbackServicePort submitFeedback;
    private final ListFeedbackServicePort listFeedback;
    private final PromoteFeedbackServicePort promoteFeedback;

    public FeedbackController(
            SubmitFeedbackServicePort submitFeedback,
            ListFeedbackServicePort listFeedback,
            PromoteFeedbackServicePort promoteFeedback) {
        this.submitFeedback = submitFeedback;
        this.listFeedback = listFeedback;
        this.promoteFeedback = promoteFeedback;
    }

    @PutMapping("/{turnId}")
    public Mono<FeedbackResponse> submit(
            @PathVariable String turnId,
            @RequestBody SubmitFeedbackRequest request) {
        return Mono.defer(() -> submitFeedback.submit(FeedbackRestMapper.toSubmitCommand(turnId, request)))
                .map(FeedbackRestMapper::toResponse);
    }

    @GetMapping
    public Mono<FeedbackListResponse> list(
            @RequestParam(required = false) String rating,
            @RequestParam(required = false) Boolean promoted,
            @RequestParam(required = false) Integer limit) {
        return Mono.defer(() -> listFeedback.list(FeedbackRestMapper.toListQuery(rating, promoted, limit)))
                .map(FeedbackRestMapper::toListResponse);
    }

    @PostMapping("/{turnId}/promote")
    public Mono<FeedbackItemResponse> promote(
            @PathVariable String turnId,
            @RequestBody PromoteFeedbackRequest request) {
        return Mono.defer(() -> promoteFeedback.promote(FeedbackRestMapper.toPromoteCommand(turnId, request)))
                .map(FeedbackRestMapper::toItemResponse);
    }
}
