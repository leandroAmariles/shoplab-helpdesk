import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EVAL_HISTORY_URL } from '../eval-api.client';
import { EvalStore } from '../eval-store';
import { RUN_BODY } from '../eval-test-data';
import { FailingCases } from './failing-cases';

const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736';

function result(
  caseId: string,
  category: string,
  scores: Record<string, number | null>,
  extra: Record<string, unknown> = {},
) {
  return {
    case_id: caseId,
    revision: caseId.padEnd(64, 'a'),
    category,
    question: `Pregunta del caso ${caseId}`,
    reference_answer: `Respuesta de referencia de ${caseId}`,
    expected_documents: ['politica-devoluciones'],
    case_kind: 'answered',
    scores,
    retrieved_documents: ['politica-envios', 'politica-devoluciones'],
    abstained: false,
    trace_id: null,
    trace_url: null,
    ...extra,
  };
}

const QUALITY_BODY = {
  ...RUN_BODY,
  results: [
    result('G044', 'returns', { faithfulness: 0.2, answer_relevancy: null }, {
      trace_id: TRACE,
      trace_url: `http://localhost:3001/trace/${TRACE}`,
    }),
    result('G045', 'returns', { faithfulness: 1 }, { case_kind: 'unanswered', expected_documents: [] }),
    result('G046', 'payments', { faithfulness: 0.3, answer_relevancy: 0.4 }, { trace_id: 'tracesinurl' }),
    result('G047', 'payments', { faithfulness: 0.9, answer_relevancy: 0.9 }),
  ],
};

const RETRIEVAL_BODY = {
  ...RUN_BODY,
  run: {
    ...RUN_BODY.run,
    run_id: 'ret-1',
    kind: 'RETRIEVAL',
    judge: null,
    evaluator: null,
    retrieval_config: {
      top_k: 4,
      similarity_threshold: 0.6,
      max_context_characters: 8000,
      depth: 50,
      ks: [1, 3, 5],
    },
  },
  results: [
    result('G001', 'returns', { hit_at_1: 0, hit_at_3: 0, hit_at_5: 1, reciprocal_rank: 0.25 }),
    result('G002', 'returns', { hit_at_1: 0, hit_at_3: 1, hit_at_5: 1, reciprocal_rank: 0.5 }),
    result('G003', 'payments', { hit_at_1: 1, hit_at_3: 1, hit_at_5: 1, reciprocal_rank: 1 }),
  ],
};

describe('FailingCases', () => {
  let fixture: ComponentFixture<FailingCases>;
  let root: HTMLElement;
  let store: EvalStore;
  let http: HttpTestingController;

  function open(id: string, body: object): void {
    store.selectRun(id);
    http.expectOne(`${EVAL_HISTORY_URL}/runs/${id}`).flush(body);
    fixture.detectChanges();
  }

  function text(): string {
    return (root.textContent ?? '').replace(/\s+/g, ' ');
  }

  function rows(): HTMLElement[] {
    return Array.from(root.querySelectorAll<HTMLElement>('tbody tr.case-row'));
  }

  function setThreshold(value: string): void {
    const input = root.querySelector('input#threshold') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function choose(selector: string, value: string): void {
    const select = root.querySelector(selector) as HTMLSelectElement;
    select.value = value;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [EvalStore, provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(EvalStore);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(FailingCases);
    root = fixture.nativeElement as HTMLElement;
  });

  afterEach(() => http.verify());

  describe('in quality', () => {
    beforeEach(() => open('t031-1', QUALITY_BODY));

    it('says how many cases fail out of how many were measured', () => {
      expect(root.querySelector('.status')?.textContent).toContain('2 casos fallan de 96 medidos');
      expect(root.querySelector('.status')?.getAttribute('role')).toBe('status');
    });

    it('has a threshold control that starts at 0.5 and explains what it means', () => {
      const input = root.querySelector('input#threshold') as HTMLInputElement;

      expect(Number(input.value)).toBe(0.5);
      expect(input.min).toBe('0');
      expect(input.max).toBe('1');
      expect(input.step).toBe('0.05');
      expect(root.querySelector('label[for="threshold"]')).not.toBeNull();
      expect(text()).toContain('por debajo de este umbral');
    });

    it('lists the failing cases worst first, with id, category and question', () => {
      expect(rows().map((row) => row.querySelector('th')?.textContent?.trim())).toEqual(['G044', 'G046']);
      expect(rows()[0].textContent).toContain('returns');
      expect(rows()[0].textContent).toContain('Pregunta del caso G044');
    });

    it('moving the threshold updates the list at once and sends no request', () => {
      setThreshold('0.25');

      expect(rows().map((row) => row.querySelector('th')?.textContent?.trim())).toEqual(['G044']);
      expect(root.querySelector('.status')?.textContent).toContain('1 caso falla de 96 medidos');
      http.expectNone(() => true);
    });

    it('says explicitly that no case fails at a threshold of zero', () => {
      setThreshold('0');

      expect(rows()).toHaveLength(0);
      expect(root.querySelector('.status')?.textContent).toContain('Ningún caso falla con este criterio');
    });

    it('keeps the threshold inside its range', () => {
      setThreshold('7');
      expect(store.threshold()).toBe(1);
      setThreshold('-1');
      expect(store.threshold()).toBe(0);
    });

    it('marks the metrics below the threshold with an icon and a text, and shows «sin valor» for the empty ones', () => {
      const first = rows()[0];
      const below = first.querySelectorAll('.score.below');

      expect(below).toHaveLength(1);
      expect(below[0].textContent).toContain('Fidelidad');
      expect(below[0].textContent).toContain('0,2000');
      expect(below[0].textContent).toContain('por debajo del umbral');
      expect(below[0].querySelector('mat-icon')?.getAttribute('aria-hidden')).toBe('true');
      expect(first.textContent).toContain('Relevancia de la respuesta');
      expect(first.textContent).toContain('sin valor');
      expect(first.querySelectorAll('.score.below')).toHaveLength(1);
    });

    it('filters by category and by the metric that failed', () => {
      choose('select#category-filter', 'payments');
      expect(rows().map((row) => row.querySelector('th')?.textContent?.trim())).toEqual(['G046']);

      choose('select#category-filter', '');
      choose('select#metric-filter', 'answer_relevancy');
      expect(rows().map((row) => row.querySelector('th')?.textContent?.trim())).toEqual(['G046']);
    });

    it('offers the categories and the metrics that the run has', () => {
      const categories = Array.from(root.querySelectorAll('select#category-filter option')).map((o) =>
        o.textContent?.trim(),
      );
      const metrics = Array.from(root.querySelectorAll('select#metric-filter option')).map((o) =>
        o.textContent?.trim(),
      );

      expect(categories).toEqual(['Todas', 'payments', 'returns']);
      expect(metrics).toEqual(['Cualquiera', 'Fidelidad', 'Relevancia de la respuesta']);
    });

    it('orders by case id or by category', () => {
      choose('select#sort', 'caseId');
      expect(rows().map((row) => row.querySelector('th')?.textContent?.trim())).toEqual(['G044', 'G046']);

      choose('select#sort', 'category');
      expect(rows().map((row) => row.querySelector('th')?.textContent?.trim())).toEqual(['G046', 'G044']);
    });

    it('opens a case with its reference answer, the expected and the retrieved documents and the abstention', () => {
      (rows()[0].querySelector('button.toggle') as HTMLButtonElement).click();
      fixture.detectChanges();

      const detail = root.querySelector('tr.case-detail') as HTMLElement;
      expect(detail.textContent).toContain('Respuesta de referencia de G044');
      const expected = Array.from(detail.querySelectorAll('.expected li')).map((li) => li.textContent?.trim());
      expect(expected.join(' ')).toContain('politica-devoluciones');
      expect(expected.join(' ')).toContain('encontrado');
      const retrieved = Array.from(detail.querySelectorAll('.retrieved li'));
      expect(retrieved).toHaveLength(2);
      expect(retrieved[0].textContent).toContain('1.');
      expect(retrieved[0].textContent).toContain('politica-envios');
      expect(retrieved[0].textContent).toContain('no esperado');
      expect(retrieved[1].textContent).toContain('esperado');
      expect(detail.textContent).toContain('El asistente respondió');
      expect(rows()[0].querySelector('button.toggle')?.getAttribute('aria-expanded')).toBe('true');
    });

    it('closes the case when its button is pressed again, and opens one at a time', () => {
      const first = rows()[0].querySelector('button.toggle') as HTMLButtonElement;
      first.click();
      fixture.detectChanges();
      (rows()[1].querySelector('button.toggle') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(root.querySelectorAll('tr.case-detail')).toHaveLength(1);
      expect(root.querySelector('tr.case-detail')?.textContent).toContain('G046');

      (rows()[1].querySelector('button.toggle') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(root.querySelectorAll('tr.case-detail')).toHaveLength(0);
    });

    it('says that the assistant abstained when it did', () => {
      store.setThreshold(1);
      fixture.detectChanges();
      const rewritten = {
        ...QUALITY_BODY,
        results: [result('G090', 'returns', { faithfulness: 0.1 }, { abstained: true })],
      };
      open('abstain-1', rewritten);

      (rows()[0].querySelector('button.toggle') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(root.querySelector('tr.case-detail')?.textContent).toContain('El asistente se abstuvo');
    });

    it('links to the trace in a new tab without leaking the opener', () => {
      (rows()[0].querySelector('button.toggle') as HTMLButtonElement).click();
      fixture.detectChanges();

      const link = root.querySelector('tr.case-detail a.trace-link') as HTMLAnchorElement;
      expect(link.textContent).toContain('Ver traza en Langfuse');
      expect(link.getAttribute('href')).toBe(`http://localhost:3001/trace/${TRACE}`);
      expect(link.getAttribute('target')).toBe('_blank');
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    });

    it('shows the id of the trace to copy when the link cannot be built', () => {
      (rows()[1].querySelector('button.toggle') as HTMLButtonElement).click();
      fixture.detectChanges();

      const detail = root.querySelector('tr.case-detail') as HTMLElement;
      expect(detail.querySelector('a.trace-link')).toBeNull();
      expect(detail.querySelector('.trace-id')?.textContent).toContain('tracesinurl');
      expect(detail.querySelector('button.copy')?.textContent).toContain('Copiar');
    });

    it('copies the id of the trace with the button', async () => {
      const writeText = vi.fn().mockResolvedValue(undefined);
      Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
      (rows()[1].querySelector('button.toggle') as HTMLButtonElement).click();
      fixture.detectChanges();

      (root.querySelector('button.copy') as HTMLButtonElement).click();

      expect(writeText).toHaveBeenCalledWith('tracesinurl');
    });

    it('never shows the text of the answer or of the fragments and says where that content lives', () => {
      (rows()[0].querySelector('button.toggle') as HTMLButtonElement).click();
      fixture.detectChanges();

      expect(text()).toContain('viven en la traza de Langfuse');
      expect(text()).not.toContain('Respuesta del asistente:');
      expect(root.querySelector('tr.case-detail')?.textContent).not.toContain('Fragmentos recuperados');
    });

    it('is inside a scrollable region with a name', () => {
      const region = root.querySelector('.cases-region') as HTMLElement;

      expect(region.getAttribute('role')).toBe('region');
      expect(region.getAttribute('tabindex')).toBe('0');
      expect(region.getAttribute('aria-label')).toContain('Casos que fallan');
    });
  });

  describe('in retrieval', () => {
    beforeEach(() => open('ret-1', RETRIEVAL_BODY));

    it('has a selector of k that starts at the largest and no threshold', () => {
      const select = root.querySelector('select#k-select') as HTMLSelectElement;

      expect(select.value).toBe('5');
      expect(Array.from(select.options).map((option) => option.value)).toEqual(['1', '3', '5']);
      expect(root.querySelector('input#threshold')).toBeNull();
      expect(root.querySelector('select#metric-filter')).toBeNull();
    });

    it('lists the cases that did not find their documents within the chosen k', () => {
      expect(rows()).toHaveLength(0);
      expect(root.querySelector('.status')?.textContent).toContain('Ningún caso falla con este criterio');

      choose('select#k-select', '3');
      expect(rows().map((row) => row.querySelector('th')?.textContent?.trim())).toEqual(['G001']);
      expect(rows()[0].textContent).toContain('no encontró ningún documento esperado');

      choose('select#k-select', '1');
      expect(rows().map((row) => row.querySelector('th')?.textContent?.trim())).toEqual(['G001', 'G002']);
    });
  });

  it('shows nothing before a run is chosen', () => {
    expect(root.querySelector('.status')).toBeNull();
    expect(root.querySelector('table')).toBeNull();
  });
});
