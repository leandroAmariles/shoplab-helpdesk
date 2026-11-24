import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import type {
  FeedbackItemResponse,
  FeedbackListResponse,
  PromoteFeedbackRequest,
} from '@leandroamariles/shoplab-contracts';
import { Observable } from 'rxjs';
import { HANDLES_OWN_ERRORS } from '../../../core/api/api-error';

export const FEEDBACK_API_URL = '/api/chat/feedback';

export type FeedbackListItem = FeedbackItemResponse;
export type FeedbackListResult = FeedbackListResponse;
export type PromoteFeedbackDraft = PromoteFeedbackRequest;

function ownErrors(): HttpContext {
  return new HttpContext().set(HANDLES_OWN_ERRORS, true);
}

/** Outbound HTTP client for listing negative feedback and promoting it to golden dataset cases (LAB-33, LAB-106). */
@Injectable({ providedIn: 'root' })
export class FeedbackApiClient {
  private readonly http = inject(HttpClient);

  listFeedback(promoted?: boolean | null, limit = 100): Observable<FeedbackListResponse> {
    let params = new HttpParams().set('rating', 'DOWN').set('limit', limit);
    if (promoted !== undefined && promoted !== null) {
      params = params.set('promoted', String(promoted));
    }
    return this.http.get<FeedbackListResponse>(FEEDBACK_API_URL, {
      params,
      context: ownErrors(),
    });
  }

  promoteFeedback(turnId: string, draft: PromoteFeedbackRequest): Observable<FeedbackItemResponse> {
    return this.http.post<FeedbackItemResponse>(
      `${FEEDBACK_API_URL}/${encodeURIComponent(turnId)}/promote`,
      draft,
      { context: ownErrors() },
    );
  }
}
