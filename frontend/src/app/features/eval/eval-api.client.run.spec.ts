import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { HANDLES_OWN_ERRORS } from '../../core/api/api-error';
import { EVAL_HISTORY_URL, EvalApiClient } from './eval-api.client';
import { RUN_BODY } from './eval-test-data';
import { RunDetail } from './eval-history';

describe('EvalApiClient.getRun', () => {
  let client: EvalApiClient;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    client = TestBed.inject(EvalApiClient);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('asks for the run by its id and handles its own errors', () => {
    client.getRun('t031-1').subscribe();

    const request = http.expectOne(`${EVAL_HISTORY_URL}/runs/t031-1`);
    expect(request.request.method).toBe('GET');
    expect(request.request.context.get(HANDLES_OWN_ERRORS)).toBe(true);
    request.flush(RUN_BODY);
  });

  it('encodes the id so that nothing else can be added to the path', () => {
    client.getRun('a/../b').subscribe();

    http.expectOne(`${EVAL_HISTORY_URL}/runs/a%2F..%2Fb`).flush(RUN_BODY);
  });

  describe('translated run', () => {
    let detail: RunDetail;

    beforeEach(() => {
      client.getRun('t031-1').subscribe((value) => (detail = value));
      http.expectOne(`${EVAL_HISTORY_URL}/runs/t031-1`).flush(RUN_BODY);
    });

    it('has the parameters in camelCase and keeps the unknown ones as null', () => {
      expect(detail.run.runId).toBe('t031-1');
      expect(detail.run.recordedAt).toBe('2026-11-21T14:11:04Z');
      expect(detail.run.commitSha).toBeNull();
      expect(detail.run.assistantModel).toBeNull();
      expect(detail.run.datasetHash).toBe('9'.repeat(64));
      expect(detail.run.casesExcluded).toBe(24);
      expect(detail.run.embeddingModel).toBe('nomic-embed-text');
      expect(detail.run.prompts[0]).toEqual({
        name: 'chat-system',
        version: 3,
        source: 'LANGFUSE',
        pinned: true,
      });
      expect(detail.run.judge).toEqual({
        model: 'judge',
        underlyingModel: 'qwen3:8b',
        temperature: 0,
        seed: 7,
        maxInputChars: 6000,
      });
      expect(detail.run.evaluator).toEqual({
        library: 'ragas',
        libraryVersion: '0.3.5',
        judgmentInstructionsVersion: '2',
      });
      expect(detail.run.retrievalConfig).toEqual({
        topK: 4,
        similarityThreshold: 0.6,
        maxContextCharacters: 8000,
        depth: null,
        ks: null,
      });
    });

    it('has the summary of each scope, and a metric without value stays a gap', () => {
      expect(detail.summary[0]).toEqual({
        scope: 'all',
        cases: 96,
        metrics: { faithfulness: { mean: 0.7134, n: 96 } },
      });
      expect(detail.summary[1].metrics['faithfulness']).toEqual({ mean: null, n: 0 });
    });

    it('has the results with the scores as they came: a null is not a zero', () => {
      const [first, second] = detail.results;

      expect(first.caseId).toBe('G044');
      expect(first.referenceAnswer).toBe('Tienes 30 días naturales.');
      expect(first.expectedDocuments).toEqual(['politica-devoluciones']);
      expect(first.caseKind).toBe('answered');
      expect(first.scores['faithfulness']).toBe(0.2);
      expect(first.scores['answer_relevancy']).toBeNull();
      expect(first.retrievedDocuments).toEqual(['politica-envios', 'politica-devoluciones']);
      expect(first.traceId).toBe('4bf92f3577b34da6a3ce929d0e0e4736');
      expect(first.traceUrl).toBe('http://localhost:3001/trace/4bf92f3577b34da6a3ce929d0e0e4736');
      expect(second.abstained).toBeNull();
      expect(second.traceId).toBeNull();
      expect(second.traceUrl).toBeNull();
    });
  });

  it('keeps the judge and the evaluator as null in a retrieval run', () => {
    let detail: RunDetail | undefined;
    client.getRun('t031-2').subscribe((value) => (detail = value));

    http.expectOne(`${EVAL_HISTORY_URL}/runs/t031-2`).flush({
      ...RUN_BODY,
      run: {
        ...RUN_BODY.run,
        kind: 'RETRIEVAL',
        judge: null,
        evaluator: null,
        retrieval_config: { ...RUN_BODY.run.retrieval_config, depth: 50, ks: [1, 3, 5] },
      },
    });

    expect(detail?.run.judge).toBeNull();
    expect(detail?.run.evaluator).toBeNull();
    expect(detail?.run.retrievalConfig.depth).toBe(50);
    expect(detail?.run.retrievalConfig.ks).toEqual([1, 3, 5]);
  });
});
