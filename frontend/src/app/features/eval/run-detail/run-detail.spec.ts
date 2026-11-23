import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RunDetail } from '../eval-history';
import { RunDetailView } from './run-detail';

function quality(): RunDetail {
  return {
    run: {
      runId: 't031-1',
      kind: 'QUALITY',
      startedAt: '2026-11-21T14:02:11Z',
      finishedAt: '2026-11-21T14:11:03Z',
      recordedAt: '2026-11-21T14:11:04Z',
      origin: 'dev',
      commitSha: null,
      datasetHash: '9f2c'.repeat(16),
      casesMeasured: 96,
      casesExcluded: 24,
      assistantModel: null,
      embeddingModel: 'nomic-embed-text',
      prompts: [
        { name: 'chat-system', version: 3, source: 'LANGFUSE', pinned: true },
        { name: 'chat-context', version: 2, source: 'LANGFUSE', pinned: false },
        { name: 'chat-empty-context', version: null, source: 'DEFAULT', pinned: false },
      ],
      judge: {
        model: 'judge',
        underlyingModel: 'qwen3:8b',
        temperature: 0,
        seed: 7,
        maxInputChars: 6000,
      },
      evaluator: { library: 'ragas', libraryVersion: '0.3.5', judgmentInstructionsVersion: '2' },
      retrievalConfig: {
        topK: 4,
        similarityThreshold: 0.6,
        maxContextCharacters: 8000,
        depth: null,
        ks: null,
      },
    },
    summary: [
      {
        scope: 'all',
        cases: 96,
        metrics: {
          faithfulness: { mean: 0.71344, n: 96 },
          answer_relevancy: { mean: 0.8, n: 70 },
          context_precision: { mean: null, n: 0 },
        },
      },
      {
        scope: 'returns',
        cases: 18,
        metrics: {
          faithfulness: { mean: 0.69, n: 18 },
          answer_relevancy: { mean: 0, n: 9 },
          context_precision: { mean: null, n: 0 },
        },
      },
    ],
    results: [],
  };
}

function retrieval(): RunDetail {
  const base = quality();
  return {
    ...base,
    run: {
      ...base.run,
      runId: 'ret-1',
      kind: 'RETRIEVAL',
      commitSha: '5fae385e1c0f9a8b7d6c5e4f3a2b1c0d9e8f7a6b',
      assistantModel: 'chat',
      prompts: [],
      judge: null,
      evaluator: null,
      retrievalConfig: {
        topK: 4,
        similarityThreshold: 0.6,
        maxContextCharacters: 8000,
        depth: 50,
        ks: [1, 3, 5],
      },
    },
    summary: [
      {
        scope: 'all',
        cases: 96,
        metrics: { reciprocal_rank: { mean: 0.5, n: 96 }, hit_at_3: { mean: 0.75, n: 80 } },
      },
    ],
  };
}

describe('RunDetailView', () => {
  let fixture: ComponentFixture<RunDetailView>;
  let root: HTMLElement;

  function render(detail: RunDetail): void {
    fixture.componentRef.setInput('detail', detail);
    fixture.detectChanges();
  }

  function text(): string {
    return (root.textContent ?? '').replace(/\s+/g, ' ');
  }

  beforeEach(() => {
    TestBed.configureTestingModule({});
    fixture = TestBed.createComponent(RunDetailView);
    root = fixture.nativeElement as HTMLElement;
  });

  describe('how it was measured', () => {
    it('shows each prompt with its numbered version and whether it was pinned or the production one', () => {
      render(quality());

      expect(text()).toContain('chat-system v3 · fijada por petición');
      expect(text()).toContain('chat-context v2 · producción');
    });

    it('shows a prompt that is the packaged text as such, without inventing a version', () => {
      render(quality());

      expect(text()).toContain('chat-empty-context · texto por defecto');
      expect(text()).not.toContain('chat-empty-context v');
    });

    it('writes «desconocido» for the model of the assistant and the commit when they are not known', () => {
      render(quality());

      const definition = (name: string) =>
        Array.from(root.querySelectorAll('dt'))
          .find((dt) => dt.textContent?.trim() === name)
          ?.nextElementSibling?.textContent?.trim();
      expect(definition('Modelo del asistente')).toBe('desconocido');
      expect(definition('Commit')).toBe('desconocido');
    });

    it('shows the model of the assistant and a short commit when they are known', () => {
      render(retrieval());

      expect(text()).toContain('chat');
      expect(text()).toContain('5fae385');
      expect(text()).not.toContain('5fae385e1c0f9a8b7d6c');
    });

    it('shows the embeddings, the origin, the dataset and the dates', () => {
      render(quality());

      expect(text()).toContain('nomic-embed-text');
      expect(text()).toContain('dev');
      expect(text()).toContain('9f2c9f2c9f2c');
      expect(text()).toContain('Fecha');
    });

    it('shows the judge, its settings and the library only in a quality run', () => {
      render(quality());

      expect(text()).toContain('judge (qwen3:8b)');
      expect(text()).toContain('temperatura 0');
      expect(text()).toContain('semilla 7');
      expect(text()).toContain('ragas 0.3.5');
      expect(text()).toContain('instrucciones de juicio v2');

      render(retrieval());

      expect(text()).not.toContain('judge');
      expect(text()).not.toContain('ragas');
    });

    it('shows how the assistant retrieved, and the depth and the ks only in retrieval', () => {
      render(quality());
      expect(text()).toContain('top-k 4');
      expect(text()).toContain('umbral 0,6');
      expect(text()).toContain('contexto 8000');
      expect(text()).not.toContain('profundidad');

      render(retrieval());
      expect(text()).toContain('profundidad 50');
      expect(text()).toContain('k: 1, 3, 5');
    });

    it('says how many cases were measured and excluded, and how many have no score in each metric', () => {
      render(quality());

      expect(text()).toContain('96 medidos');
      expect(text()).toContain('24 excluidos');
      expect(text()).toContain('sin puntuar');
      expect(text()).toContain('Fidelidad: 0');
      expect(text()).toContain('Relevancia de la respuesta: 26');
      expect(text()).toContain('Precisión del contexto: 96');
    });
  });

  describe('summary', () => {
    it('has a row for the whole set first and then for each category', () => {
      render(quality());

      const rows = Array.from(root.querySelectorAll('.summary tbody tr')).map((row) =>
        row.querySelector('th')?.textContent?.trim(),
      );
      expect(rows).toEqual(['Conjunto completo', 'returns']);
    });

    it('shows each mean with four decimals and the cases it was computed with', () => {
      render(quality());

      const first = root.querySelector('.summary tbody tr')?.textContent ?? '';
      expect(first).toContain('0,7134');
      expect(first).toContain('96 casos');
      expect(first).toContain('0,8000');
      expect(first).toContain('70 casos');
    });

    it('writes «sin valor» where the mean has no value, and a real zero as zero', () => {
      render(quality());

      const category = root.querySelectorAll('.summary tbody tr')[1].textContent ?? '';
      expect(category).toContain('sin valor');
      expect(category).toContain('0,0000');
      expect(category).toContain('9 casos');
    });

    it('has a column for each metric the run has', () => {
      render(retrieval());

      const headers = Array.from(root.querySelectorAll('.summary thead th')).map((th) =>
        th.textContent?.trim(),
      );
      expect(headers).toContain('MRR');
      expect(headers).toContain('Hit rate@3');
    });

    it('is inside a scrollable region with a name', () => {
      render(quality());

      const region = root.querySelector('.summary-region') as HTMLElement;
      expect(region.getAttribute('role')).toBe('region');
      expect(region.getAttribute('tabindex')).toBe('0');
      expect(region.getAttribute('aria-label')).toContain('Resumen');
    });
  });
});
