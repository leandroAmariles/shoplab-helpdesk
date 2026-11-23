import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FEEDBACK_API_URL, FeedbackListItem } from './feedback-api.client';
import { FeedbackPromotion } from './feedback-promotion';

const SAMPLE_DOWN_ITEM: FeedbackListItem = {
  turnId: '2b6f1a04-9c44-4d30-9f51-8f2a10c4c7a9',
  conversationId: '9c3a2011-7e51-4a0e-88a4-1a1c80f7c111',
  question: '¿Cuántos días tengo para devolver una compra?',
  answer: 'Tienes 15 días.',
  deliveredDocuments: ['returns-01-return-policy'],
  traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
  rating: 'DOWN',
  comment: 'La política dice 30 días, no 15.',
  createdAt: '2026-11-23T15:10:00Z',
  updatedAt: '2026-11-23T15:10:05Z',
  promotedCaseId: null,
  promotedCaseJsonl: null,
  promotedAt: null,
};

describe('FeedbackPromotion', () => {
  let fixture: ComponentFixture<FeedbackPromotion>;
  let http: HttpTestingController;
  let dom: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [FeedbackPromotion],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FeedbackPromotion);
    dom = fixture.nativeElement as HTMLElement;
    await fixture.whenStable();
  });

  afterEach(() => {
    http.verify();
  });

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('does not request feedback automatically on init and loads on demand when button is clicked', async () => {
    http.expectNone(FEEDBACK_API_URL);

    const loadBtn = dom.querySelector<HTMLButtonElement>('button.load-feedback-btn')!;
    expect(loadBtn.textContent).toContain('Cargar respuestas con 👎');
    loadBtn.click();

    const req = http.expectOne(
      (r) =>
        r.url === FEEDBACK_API_URL &&
        r.params.get('rating') === 'DOWN' &&
        r.params.get('promoted') === 'false',
    );
    req.flush({
      total: 1,
      pendingCount: 1,
      promotedCount: 0,
      suggestedNextCaseId: 'G121',
      items: [SAMPLE_DOWN_ITEM],
    });
    await settle();

    expect(dom.textContent).toContain('¿Cuántos días tengo para devolver una compra?');
    expect(dom.textContent).toContain('La política dice 30 días, no 15.');
    expect(dom.textContent).toContain('G121');
  });

  it('preloads the promotion form with suggestedNextCaseId, question and deliveredDocuments and promotes to G121', async () => {
    dom.querySelector<HTMLButtonElement>('button.load-feedback-btn')!.click();
    http
      .expectOne((r) => r.url === FEEDBACK_API_URL)
      .flush({
        total: 1,
        pendingCount: 1,
        promotedCount: 0,
        suggestedNextCaseId: 'G121',
        items: [SAMPLE_DOWN_ITEM],
      });
    await settle();

    dom.querySelector<HTMLButtonElement>('button.open-promote-btn')!.click();
    await settle();

    const caseIdInput = dom.querySelector<HTMLInputElement>(
      `#case-id-${SAMPLE_DOWN_ITEM.turnId}`,
    )!;
    const questionInput = dom.querySelector<HTMLTextAreaElement>(
      `#question-${SAMPLE_DOWN_ITEM.turnId}`,
    )!;
    const docsInput = dom.querySelector<HTMLInputElement>(
      `#expected-docs-${SAMPLE_DOWN_ITEM.turnId}`,
    )!;
    const answerInput = dom.querySelector<HTMLTextAreaElement>(
      `#reference-answer-${SAMPLE_DOWN_ITEM.turnId}`,
    )!;

    expect(caseIdInput.value).toBe('G121');
    expect(questionInput.value).toBe(SAMPLE_DOWN_ITEM.question);
    expect(docsInput.value).toBe('returns-01-return-policy');
    expect(answerInput.value).toBe('');

    const referenceAnswer =
      'Puedes solicitar la devolución dentro de los 30 días naturales siguientes a la entrega.';
    answerInput.value = referenceAnswer;
    answerInput.dispatchEvent(new Event('input'));
    await settle();

    dom.querySelector<HTMLButtonElement>('button.confirm-promote-btn')!.click();

    const promoteReq = http.expectOne(
      `${FEEDBACK_API_URL}/${SAMPLE_DOWN_ITEM.turnId}/promote`,
    );
    expect(promoteReq.request.body).toEqual({
      caseId: 'G121',
      question: SAMPLE_DOWN_ITEM.question,
      referenceAnswer,
      expectedDocuments: ['returns-01-return-policy'],
      category: 'returns',
    });

    const jsonl =
      '{"id": "G121", "question": "¿Cuántos días tengo para devolver una compra?", ' +
      '"reference_answer": "' +
      referenceAnswer +
      '", "expected_documents": ["returns-01-return-policy"], "category": "returns"}';

    promoteReq.flush({
      ...SAMPLE_DOWN_ITEM,
      promotedCaseId: 'G121',
      promotedCaseJsonl: jsonl,
      promotedAt: '2026-11-23T15:12:00Z',
    });
    await settle();

    expect(dom.textContent).toContain('Promovido a G121');
    expect(dom.textContent).toContain(jsonl);
    expect(dom.textContent).toContain('G122');
    expect(dom.querySelector('button.open-promote-btn')).toBeNull();
  });

  it('displays the backend validation rule code when promotion fails with 422', async () => {
    dom.querySelector<HTMLButtonElement>('button.load-feedback-btn')!.click();
    http
      .expectOne((r) => r.url === FEEDBACK_API_URL)
      .flush({
        total: 1,
        pendingCount: 1,
        promotedCount: 0,
        suggestedNextCaseId: 'G121',
        items: [SAMPLE_DOWN_ITEM],
      });
    await settle();

    dom.querySelector<HTMLButtonElement>('button.open-promote-btn')!.click();
    await settle();

    dom.querySelector<HTMLButtonElement>('button.confirm-promote-btn')!.click();
    http
      .expectOne(`${FEEDBACK_API_URL}/${SAMPLE_DOWN_ITEM.turnId}/promote`)
      .flush(
        {
          code: 'SLHD_CHAT_00014',
          detail: 'G-ANSWER-LENGTH: la respuesta de referencia debe tener entre 20 y 800 caracteres',
        },
        { status: 422, statusText: 'Unprocessable Content' },
      );
    await settle();

    const alert = dom.querySelector('.promote-error[role="alert"]');
    expect(alert?.textContent).toContain('G-ANSWER-LENGTH');
    expect(dom.querySelector('form.promote-form')).not.toBeNull();
  });
});
