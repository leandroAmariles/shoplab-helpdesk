import { Injectable } from '@angular/core';
import { Observable, Subscriber } from 'rxjs';
import { ChatErrorKind, ChatStreamError } from './chat-error';
import { ChatSource, ChatStreamEvent } from './chat-message';
import { createSseParser, SseEvent } from './sse-parser';

const CHAT_URL = '/api/chat';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value !== '';
}

/** The sources of the `sources` event; `null` when the list is empty or any entry breaks the contract (LAB-23). */
function toSources(value: unknown): ChatSource[] | null {
  if (!Array.isArray(value) || value.length === 0) {
    return null;
  }
  const sources: ChatSource[] = [];
  for (const entry of value as unknown[]) {
    if (
      !isRecord(entry) ||
      typeof entry['number'] !== 'number' ||
      !Number.isInteger(entry['number']) ||
      entry['number'] < 1 ||
      !isNonEmptyString(entry['title']) ||
      typeof entry['section'] !== 'string' ||
      !isNonEmptyString(entry['text'])
    ) {
      return null;
    }
    sources.push({
      number: entry['number'],
      title: entry['title'],
      section: entry['section'],
      text: entry['text'],
    });
  }
  return sources;
}

/** Turns a raw SSE event into a typed one; `null` for anything outside the LAB-12 contract. */
function toChatEvent(raw: SseEvent): ChatStreamEvent | null {
  let payload: unknown;
  try {
    payload = JSON.parse(raw.data);
  } catch {
    // Never log `raw.data`: it may hold the customer's conversation.
    console.warn('Chat stream: evento ignorado (JSON inválido).');
    return null;
  }
  if (isRecord(payload)) {
    if (raw.event === 'sources') {
      const sources = toSources(payload['sources']);
      if (sources !== null) {
        return { type: 'sources', sources };
      }
    }
    if (raw.event === 'token' && typeof payload['content'] === 'string') {
      return { type: 'token', content: payload['content'] };
    }
    if (raw.event === 'done' && typeof payload['fragments'] === 'number') {
      return { type: 'done', fragments: payload['fragments'] };
    }
    if (
      raw.event === 'error' &&
      typeof payload['code'] === 'string' &&
      typeof payload['message'] === 'string'
    ) {
      return { type: 'error', code: payload['code'], message: payload['message'] };
    }
  }
  console.warn(`Chat stream: evento ignorado (${raw.event}).`);
  return null;
}

function kindOf(status: number): ChatErrorKind {
  switch (status) {
    case 400:
      return 'validation';
    case 503:
      return 'unavailable';
    // A gateway or proxy that cannot reach the backend (e.g. the dev proxy with the backend down).
    case 502:
    case 504:
      return 'network';
    default:
      return 'server';
  }
}

async function toHttpError(response: Response): Promise<ChatStreamError> {
  let code: string | undefined;
  try {
    const body: unknown = await response.json();
    if (isRecord(body) && typeof body['code'] === 'string') {
      code = body['code'];
    }
  } catch {
    // The error body is optional; the status is enough.
  }
  return new ChatStreamError(kindOf(response.status), response.status, code);
}

/** Outbound port of the chat screen: `POST /api/chat` read as a server-sent event stream. */
@Injectable({ providedIn: 'root' })
export class ChatStreamClient {
  /**
   * Cold observable: the request starts on subscribe and is cancelled on unsubscribe, which
   * makes the backend stop generating. A cancellation by the caller is never an error.
   */
  stream(message: string): Observable<ChatStreamEvent> {
    return new Observable<ChatStreamEvent>((subscriber) => {
      const controller = new AbortController();
      const reader: { current?: ReadableStreamDefaultReader<Uint8Array> } = {};

      void this.run(message, controller.signal, subscriber, reader);

      return () => {
        controller.abort();
        reader.current?.cancel().catch(() => undefined);
      };
    });
  }

  private async run(
    message: string,
    signal: AbortSignal,
    subscriber: Subscriber<ChatStreamEvent>,
    reader: { current?: ReadableStreamDefaultReader<Uint8Array> },
  ): Promise<void> {
    try {
      const response = await this.send(message, signal);
      if (!response.ok) {
        throw await toHttpError(response);
      }
      if (!response.body) {
        throw new ChatStreamError('stream');
      }
      reader.current = response.body.getReader();
      if (!(await this.pump(reader.current, subscriber))) {
        throw new ChatStreamError('stream');
      }
    } catch (error) {
      if (signal.aborted || subscriber.closed) {
        return;
      }
      subscriber.error(error instanceof ChatStreamError ? error : new ChatStreamError('stream'));
    }
  }

  private async send(message: string, signal: AbortSignal): Promise<Response> {
    try {
      return await fetch(CHAT_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify({ message }),
        signal,
      });
    } catch {
      throw new ChatStreamError('network');
    }
  }

  /** Emits the events as they complete; resolves `true` when a final event (`done`/`error`) arrived. */
  private async pump(
    reader: ReadableStreamDefaultReader<Uint8Array>,
    subscriber: Subscriber<ChatStreamEvent>,
  ): Promise<boolean> {
    const decoder = new TextDecoder();
    const parser = createSseParser();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) {
        return false;
      }
      for (const raw of parser.push(decoder.decode(value, { stream: true }))) {
        const event = toChatEvent(raw);
        if (event === null) {
          continue;
        }
        subscriber.next(event);
        // `sources` and `token` keep the stream open; only `done` and `error` end it.
        if (event.type === 'done' || event.type === 'error') {
          subscriber.complete();
          return true;
        }
      }
    }
  }
}
