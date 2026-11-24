import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import type {
  FeedbackRating,
  FeedbackResponse,
  SubmitFeedbackRequest,
} from '@leandroamariles/shoplab-contracts';
import { Observable, throwError } from 'rxjs';
import { HANDLES_OWN_ERRORS } from '../../core/api/api-error';

export const CHAT_FEEDBACK_URL = '/api/chat/feedback';

export type FeedbackSubmitResponse = FeedbackResponse;

function ownErrors(): HttpContext {
  return new HttpContext().set(HANDLES_OWN_ERRORS, true);
}

/** Outbound HTTP client for `PUT /api/chat/feedback/{turnId}` (LAB-33, LAB-106). */
@Injectable({ providedIn: 'root' })
export class ChatFeedbackClient {
  private readonly http = inject(HttpClient, { optional: true });

  submitFeedback(
    turnId: string,
    rating: FeedbackRating,
    comment?: string | null,
  ): Observable<FeedbackResponse> {
    if (!this.http) {
      return throwError(() => new Error('HttpClient is not available'));
    }
    const trimmed = comment?.trim() ?? '';
    const body: SubmitFeedbackRequest = trimmed.length > 0 ? { rating, comment: trimmed } : { rating, comment: null };
    return this.http.put<FeedbackResponse>(
      `${CHAT_FEEDBACK_URL}/${encodeURIComponent(turnId)}`,
      body,
      { context: ownErrors() },
    );
  }
}
