import { TestBed } from '@angular/core/testing';
import { Observable } from 'rxjs';
import { ChatStreamError } from './chat-error';
import { ChatStreamEvent } from './chat-message';
import { ChatStreamClient } from './chat-stream.client';

const encoder = new TextEncoder();

/** Cuts the encoded text at the given byte sizes (the last size repeats), even inside a character. */
function bytesStream(text: string, sizes: number[] = [text.length * 4]): ReadableStream<Uint8Array> {
  const bytes = encoder.encode(text);
  let offset = 0;
  let index = 0;
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (offset >= bytes.length) {
        controller.close();
        return;
      }
      const size = sizes[Math.min(index++, sizes.length - 1)];
      controller.enqueue(bytes.slice(offset, offset + size));
      offset += size;
    },
  });
}

function sse(...events: [string, object][]): string {
  return events.map(([name, data]) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`).join('');
}

function okResponse(body: ReadableStream<Uint8Array>): Response {
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

interface Outcome {
  events: ChatStreamEvent[];
  error?: unknown;
  completed: boolean;
}

function collect(source: Observable<ChatStreamEvent>): Promise<Outcome> {
  return new Promise((resolve) => {
    const outcome: Outcome = { events: [], completed: false };
    source.subscribe({
      next: (event) => outcome.events.push(event),
      error: (error: unknown) => resolve({ ...outcome, error }),
      complete: () => resolve({ ...outcome, completed: true }),
    });
  });
}

describe('ChatStreamClient', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  let client: ChatStreamClient;

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    client = TestBed.inject(ChatStreamClient);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('posts the message as JSON asking for an event stream', async () => {
    fetchMock.mockResolvedValue(okResponse(bytesStream(sse(['done', { fragments: 0 }]))));

    await collect(client.stream('Hola'));

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/chat');
    expect(init.method).toBe('POST');
    expect(init.headers).toMatchObject({
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    });
    expect(JSON.parse(init.body as string)).toEqual({ message: 'Hola' });
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it('does not call the backend until subscribed', () => {
    client.stream('Hola');

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('emits the tokens in order and completes after done', async () => {
    const body = sse(
      ['token', { content: 'Ho' }],
      ['token', { content: 'la' }],
      ['done', { fragments: 2 }],
    );
    fetchMock.mockResolvedValue(okResponse(bytesStream(body, [7, 13, 5])));

    const outcome = await collect(client.stream('Hola'));

    expect(outcome.completed).toBe(true);
    expect(outcome.events).toEqual([
      { type: 'token', content: 'Ho' },
      { type: 'token', content: 'la' },
      { type: 'done', fragments: 2 },
    ]);
  });

  it('keeps multibyte characters split between two chunks intact', async () => {
    const text = 'ñandú 😀';
    const body = sse(['token', { content: text }], ['done', { fragments: 1 }]);
    // 1-byte chunks split every multibyte character in the middle.
    fetchMock.mockResolvedValue(okResponse(bytesStream(body, [1])));

    const outcome = await collect(client.stream('Hola'));

    expect(outcome.events[0]).toEqual({ type: 'token', content: text });
  });

  it('emits the stream error event and completes', async () => {
    const body = sse(
      ['token', { content: 'Ho' }],
      ['error', { code: 'SLHD_CHAT_00003', message: 'The chat model failed' }],
    );
    fetchMock.mockResolvedValue(okResponse(bytesStream(body)));

    const outcome = await collect(client.stream('Hola'));

    expect(outcome.completed).toBe(true);
    expect(outcome.events).toEqual([
      { type: 'token', content: 'Ho' },
      { type: 'error', code: 'SLHD_CHAT_00003', message: 'The chat model failed' },
    ]);
  });

  it('ignores events after the final one', async () => {
    const body = sse(
      ['done', { fragments: 0 }],
      ['token', { content: 'late' }],
    );
    fetchMock.mockResolvedValue(okResponse(bytesStream(body)));

    const outcome = await collect(client.stream('Hola'));

    expect(outcome.events).toEqual([{ type: 'done', fragments: 0 }]);
  });

  it.each([
    [400, 'validation'],
    [503, 'unavailable'],
    [500, 'server'],
    [404, 'server'],
    [502, 'network'],
    [504, 'network'],
  ])('maps HTTP %i to a %s error', async (status, kind) => {
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify({ code: 'SLHD_X_00001', description: 'boom' }), { status }),
    );

    const outcome = await collect(client.stream('Hola'));

    expect(outcome.events).toEqual([]);
    expect(outcome.error).toBeInstanceOf(ChatStreamError);
    expect(outcome.error).toMatchObject({ kind, status, code: 'SLHD_X_00001' });
  });

  it('keeps the HTTP error even when the body is not JSON', async () => {
    fetchMock.mockResolvedValue(new Response('<html>oops</html>', { status: 500 }));

    const outcome = await collect(client.stream('Hola'));

    expect(outcome.error).toMatchObject({ kind: 'server', status: 500 });
  });

  it('maps a rejected fetch to a network error', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    const outcome = await collect(client.stream('Hola'));

    expect(outcome.error).toMatchObject({ kind: 'network' });
  });

  it('fails with a stream error when the body ends without a final event', async () => {
    fetchMock.mockResolvedValue(okResponse(bytesStream(sse(['token', { content: 'Ho' }]))));

    const outcome = await collect(client.stream('Hola'));

    expect(outcome.events).toEqual([{ type: 'token', content: 'Ho' }]);
    expect(outcome.error).toMatchObject({ kind: 'stream' });
  });

  it('fails with a stream error when the connection drops mid-response', async () => {
    let pulls = 0;
    // Erroring right after enqueue would discard the queued chunk, so fail on the second pull.
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (pulls++ === 0) {
          controller.enqueue(encoder.encode(sse(['token', { content: 'Ho' }])));
        } else {
          controller.error(new TypeError('network error'));
        }
      },
    });
    fetchMock.mockResolvedValue(okResponse(body));

    const outcome = await collect(client.stream('Hola'));

    expect(outcome.events).toEqual([{ type: 'token', content: 'Ho' }]);
    expect(outcome.error).toMatchObject({ kind: 'stream' });
  });

  it('aborts the request and releases the reader when unsubscribed, without an error', async () => {
    const cancel = vi.fn();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(sse(['token', { content: 'Ho' }])));
      },
      cancel,
    });
    fetchMock.mockResolvedValue(okResponse(body));
    const received: ChatStreamEvent[] = [];
    const onError = vi.fn();

    const subscription = client.stream('Hola').subscribe({ next: (e) => received.push(e), error: onError });
    await vi.waitFor(() => expect(received).toHaveLength(1));
    subscription.unsubscribe();
    await vi.waitFor(() => expect(cancel).toHaveBeenCalled());

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.signal?.aborted).toBe(true);
    expect(onError).not.toHaveBeenCalled();
  });

  it('does not report an error when unsubscribed before the response arrives', async () => {
    let rejectFetch: (reason: unknown) => void = () => undefined;
    fetchMock.mockImplementation(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          rejectFetch = reject;
          init.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
        }),
    );
    const onError = vi.fn();

    const subscription = client.stream('Hola').subscribe({ error: onError });
    subscription.unsubscribe();
    rejectFetch(new TypeError('late failure'));
    await Promise.resolve();

    expect(onError).not.toHaveBeenCalled();
  });

  it('ignores events with invalid JSON without logging the content', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const body =
      'event: token\ndata: {secret-bad\n\n' +
      sse(['token', { content: 'ok' }], ['done', { fragments: 1 }]);
    fetchMock.mockResolvedValue(okResponse(bytesStream(body)));

    const outcome = await collect(client.stream('Hola'));

    expect(outcome.events).toEqual([
      { type: 'token', content: 'ok' },
      { type: 'done', fragments: 1 },
    ]);
    expect(warn).toHaveBeenCalled();
    expect(JSON.stringify(warn.mock.calls)).not.toContain('secret-bad');
  });

  it('ignores unknown events and events with an unexpected shape', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const body = sse(
      ['ping', { x: 1 }],
      ['token', { content: 42 }],
      ['token', { content: 'ok' }],
      ['done', { fragments: 1 }],
    );
    fetchMock.mockResolvedValue(okResponse(bytesStream(body)));

    const outcome = await collect(client.stream('Hola'));

    expect(outcome.events).toEqual([
      { type: 'token', content: 'ok' },
      { type: 'done', fragments: 1 },
    ]);
  });
});
