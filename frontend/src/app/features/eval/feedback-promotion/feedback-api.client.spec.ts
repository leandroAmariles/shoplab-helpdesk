import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { HANDLES_OWN_ERRORS } from '../../../core/api/api-error';
import { FEEDBACK_API_URL, FeedbackApiClient } from './feedback-api.client';

describe('FeedbackApiClient', () => {
  let client: FeedbackApiClient;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    client = TestBed.inject(FeedbackApiClient);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
  });

  it('calls GET /api/chat/feedback with rating=DOWN, optional promoted filter and HANDLES_OWN_ERRORS', async () => {
    const promise = firstValueFrom(client.listFeedback(false, 50));

    const req = http.expectOne(
      (r) =>
        r.url === FEEDBACK_API_URL &&
        r.params.get('rating') === 'DOWN' &&
        r.params.get('promoted') === 'false' &&
        r.params.get('limit') === '50',
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.context.get(HANDLES_OWN_ERRORS)).toBe(true);
    req.flush({
      total: 1,
      pendingCount: 1,
      promotedCount: 0,
      suggestedNextCaseId: 'G121',
      items: [],
    });

    const result = await promise;
    expect(result.suggestedNextCaseId).toBe('G121');
  });

  it('calls POST /api/chat/feedback/{turnId}/promote with the golden case draft and HANDLES_OWN_ERRORS', async () => {
    const turnId = '2b6f1a04-9c44-4d30-9f51-8f2a10c4c7a9';
    const draft = {
      caseId: 'G121',
      question: '¿Cuántos días tengo para devolver una compra?',
      referenceAnswer:
        'Puedes solicitar la devolución dentro de los 30 días naturales siguientes a la entrega.',
      expectedDocuments: ['returns-01-return-policy'],
      category: 'returns',
    };
    const promise = firstValueFrom(client.promoteFeedback(turnId, draft));

    const req = http.expectOne(`${FEEDBACK_API_URL}/${turnId}/promote`);
    expect(req.request.method).toBe('POST');
    expect(req.request.context.get(HANDLES_OWN_ERRORS)).toBe(true);
    expect(req.request.body).toEqual(draft);
    req.flush({
      turnId,
      conversationId: '9c3a2011-7e51-4a0e-88a4-1a1c80f7c111',
      question: draft.question,
      answer: 'Tienes 15 días.',
      deliveredDocuments: ['returns-01-return-policy'],
      traceId: null,
      rating: 'DOWN',
      comment: 'Son 30',
      createdAt: '2026-11-23T15:10:00Z',
      updatedAt: '2026-11-23T15:10:00Z',
      promotedCaseId: 'G121',
      promotedCaseJsonl: '{"id": "G121"}',
      promotedAt: '2026-11-23T15:12:00Z',
    });

    const result = await promise;
    expect(result.promotedCaseId).toBe('G121');
  });
});
