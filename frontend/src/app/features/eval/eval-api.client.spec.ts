import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { HANDLES_OWN_ERRORS } from '../../core/api/api-error';
import { EVAL_HISTORY_URL, EvalApiClient } from './eval-api.client';
import { RunListing } from './eval-history';

const LIST_BODY = {
  kind: 'QUALITY',
  shown: 2,
  total: 3,
  total_all_origins: 5,
  origins: [
    { origin: 'dev', count: 3 },
    { origin: 'local', count: 2 },
  ],
  runs: [
    {
      run_id: 't031-1',
      kind: 'QUALITY',
      started_at: '2026-11-21T14:02:11Z',
      finished_at: '2026-11-21T14:11:03Z',
      origin: 'dev',
      commit_sha: null,
      assistant_model: 'chat',
      cases_measured: 96,
      prompts: [
        { name: 'chat-system', version: 3, source: 'LANGFUSE', pinned: true },
        { name: 'chat-empty-context', version: null, source: 'DEFAULT', pinned: false },
      ],
      metrics: {
        faithfulness: { mean: 0.7134, n: 96 },
        context_precision: { mean: null, n: 0 },
      },
    },
  ],
};

describe('EvalApiClient', () => {
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

  describe('listRuns', () => {
    it('asks for the kind, the origin and the limit, and handles its own errors', () => {
      client.listRuns('RETRIEVAL', 'dev', 250).subscribe();

      const request = http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/runs`);
      expect(request.request.method).toBe('GET');
      expect(request.request.params.get('kind')).toBe('RETRIEVAL');
      expect(request.request.params.get('origin')).toBe('dev');
      expect(request.request.params.get('limit')).toBe('250');
      expect(request.request.context.get(HANDLES_OWN_ERRORS)).toBe(true);
      request.flush(LIST_BODY);
    });

    it('does not send an origin when there is none', () => {
      client.listRuns('QUALITY', null, 100).subscribe();

      const request = http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/runs`);
      expect(request.request.params.has('origin')).toBe(false);
      request.flush(LIST_BODY);
    });

    it('translates the snake_case of the wire and keeps every value without value as null', () => {
      let listing: RunListing | undefined;
      client.listRuns('QUALITY', null, 100).subscribe((value) => (listing = value));

      http.expectOne((r) => r.url === `${EVAL_HISTORY_URL}/runs`).flush(LIST_BODY);

      expect(listing?.totalAllOrigins).toBe(5);
      expect(listing?.origins).toEqual([
        { origin: 'dev', count: 3 },
        { origin: 'local', count: 2 },
      ]);
      const run = listing?.runs[0];
      expect(run?.runId).toBe('t031-1');
      expect(run?.startedAt).toBe('2026-11-21T14:02:11Z');
      expect(run?.finishedAt).toBe('2026-11-21T14:11:03Z');
      expect(run?.commitSha).toBeNull();
      expect(run?.assistantModel).toBe('chat');
      expect(run?.casesMeasured).toBe(96);
      expect(run?.prompts[0]).toEqual({
        name: 'chat-system',
        version: 3,
        source: 'LANGFUSE',
        pinned: true,
      });
      expect(run?.prompts[1].version).toBeNull();
      expect(run?.metrics['faithfulness']).toEqual({ mean: 0.7134, n: 96 });
      expect(run?.metrics['context_precision']).toEqual({ mean: null, n: 0 });
    });
  });
});
