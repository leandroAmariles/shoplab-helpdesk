import { HttpClient, HttpContext } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, throwError } from 'rxjs';
import { HANDLES_OWN_ERRORS } from '../../core/api/api-error';
import { FeedbackRating } from './chat-message';

export const CHAT_FEEDBACK_URL = '/api/chat/feedback';

export interface FeedbackSubmitResponse {
  turnId: string;
  rating: FeedbackRating;
  comment: string | null;
  createdAt: string;
  updatedAt: string;
  promotedCaseId: string | null;
  promotedAt: string | null;
}

function ownErrors(): HttpContext {
  return new HttpContext().set(HANDLES_OWN_ERRORS, true);
}

/** Outbound HTTP client for `PUT /api/chat/feedback/{turnId}` (LAB-33). */
@Injectable({ providedIn: 'root' })
export class ChatFeedbackClient {
  private readonly http = inject(HttpClient, { optional: true });

  submitFeedback(
    turnId: string,
    rating: FeedbackRating,
    comment?: string | null,
  ): Observable<FeedbackSubmitResponse> {
    if (!this.http) {
      return throwError(() => new Error('HttpClient is not available'));
    }
    const trimmed = comment?.trim() ?? '';
    const body = trimmed.length > 0 ? { rating, comment: trimmed } : { rating, comment: null };
    return this.http.put<FeedbackSubmitResponse>(
      `${CHAT_FEEDBACK_URL}/${encodeURIComponent(turnId)}`,
      body,
      { context: ownErrors() },
    );
  }
}
