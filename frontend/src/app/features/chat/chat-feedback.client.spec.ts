import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { HANDLES_OWN_ERRORS } from '../../core/api/api-error';
import { CHAT_FEEDBACK_URL, ChatFeedbackClient } from './chat-feedback.client';

describe('ChatFeedbackClient', () => {
  let client: ChatFeedbackClient;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    client = TestBed.inject(ChatFeedbackClient);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
  });

  it('sends PUT /api/chat/feedback/{turnId} with rating and trimmed comment and marks HANDLES_OWN_ERRORS', async () => {
    const turnId = '2b6f1a04-9c44-4d30-9f51-8f2a10c4c7a9';
    const promise = firstValueFrom(client.submitFeedback(turnId, 'DOWN', '  Falta el plazo  '));

    const req = http.expectOne(`${CHAT_FEEDBACK_URL}/${turnId}`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.context.get(HANDLES_OWN_ERRORS)).toBe(true);
    expect(req.request.body).toEqual({ rating: 'DOWN', comment: 'Falta el plazo' });

    req.flush({
      turnId,
      rating: 'DOWN',
      comment: 'Falta el plazo',
      createdAt: '2026-11-23T15:10:00Z',
      updatedAt: '2026-11-23T15:10:00Z',
      promotedCaseId: null,
      promotedAt: null,
    });

    const response = await promise;
    expect(response.rating).toBe('DOWN');
    expect(response.comment).toBe('Falta el plazo');
  });

  it('sends null comment when the comment is omitted or blank', async () => {
    const turnId = '2b6f1a04-9c44-4d30-9f51-8f2a10c4c7a9';
    const promise = firstValueFrom(client.submitFeedback(turnId, 'UP', '   '));

    const req = http.expectOne(`${CHAT_FEEDBACK_URL}/${turnId}`);
    expect(req.request.body).toEqual({ rating: 'UP', comment: null });
    req.flush({
      turnId,
      rating: 'UP',
      comment: null,
      createdAt: '2026-11-23T15:10:00Z',
      updatedAt: '2026-11-23T15:10:00Z',
      promotedCaseId: null,
      promotedAt: null,
    });

    const response = await promise;
    expect(response.rating).toBe('UP');
    expect(response.comment).toBeNull();
  });
});
