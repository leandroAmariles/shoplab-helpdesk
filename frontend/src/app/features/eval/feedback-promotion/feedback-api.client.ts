import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { HANDLES_OWN_ERRORS } from '../../../core/api/api-error';

export const FEEDBACK_API_URL = '/api/chat/feedback';

export interface FeedbackListItem {
  turnId: string;
  conversationId: string;
  question: string;
  answer: string;
  deliveredDocuments: string[];
  traceId: string | null;
  rating: 'UP' | 'DOWN';
  comment: string | null;
  createdAt: string;
  updatedAt: string;
  promotedCaseId: string | null;
  promotedCaseJsonl: string | null;
  promotedAt: string | null;
}

export interface FeedbackListResult {
  total: number;
  pendingCount: number;
  promotedCount: number;
  suggestedNextCaseId: string;
  items: FeedbackListItem[];
}

export interface PromoteFeedbackDraft {
  caseId: string;
  question: string;
  referenceAnswer: string;
  expectedDocuments: string[];
  category: string;
}

function ownErrors(): HttpContext {
  return new HttpContext().set(HANDLES_OWN_ERRORS, true);
}

/** Outbound HTTP client for listing negative feedback and promoting it to golden dataset cases (LAB-33). */
@Injectable({ providedIn: 'root' })
export class FeedbackApiClient {
  private readonly http = inject(HttpClient);

  listFeedback(promoted?: boolean | null, limit = 100): Observable<FeedbackListResult> {
    let params = new HttpParams().set('rating', 'DOWN').set('limit', limit);
    if (promoted !== undefined && promoted !== null) {
      params = params.set('promoted', String(promoted));
    }
    return this.http.get<FeedbackListResult>(FEEDBACK_API_URL, {
      params,
      context: ownErrors(),
    });
  }

  promoteFeedback(turnId: string, draft: PromoteFeedbackDraft): Observable<FeedbackListItem> {
    return this.http.post<FeedbackListItem>(
      `${FEEDBACK_API_URL}/${encodeURIComponent(turnId)}/promote`,
      draft,
      { context: ownErrors() },
    );
  }
}
