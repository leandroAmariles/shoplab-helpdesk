import { TestBed } from '@angular/core/testing';
import { Observable, Subject } from 'rxjs';
import { ChatStreamError } from './chat-error';
import { ChatMessage, ChatStreamEvent, MAX_MESSAGE_LENGTH } from './chat-message';
import { ChatStore, FRAME_SCHEDULER } from './chat-store';
import { ChatStreamClient } from './chat-stream.client';

/** One scripted request: the test pushes events and sees whether it was cancelled. */
class FakeRequest {
  readonly events = new Subject<ChatStreamEvent>();
  unsubscribed = false;
  constructor(readonly message: string) {}
}

class FakeClient {
  readonly requests: FakeRequest[] = [];

  stream(message: string): Observable<ChatStreamEvent> {
    const request = new FakeRequest(message);
    this.requests.push(request);
    return new Observable<ChatStreamEvent>((subscriber) => {
      const inner = request.events.subscribe(subscriber);
      return () => {
        request.unsubscribed = true;
        inner.unsubscribe();
      };
    });
  }

  get last(): FakeRequest {
    return this.requests[this.requests.length - 1];
  }
}

class ManualFrames {
  readonly callbacks = new Map<number, () => void>();
  private nextId = 0;
  readonly schedule = (callback: () => void): (() => void) => {
    const id = ++this.nextId;
    this.callbacks.set(id, callback);
    return () => this.callbacks.delete(id);
  };
  get pending(): number {
    return this.callbacks.size;
  }
  run(): void {
    const callbacks = [...this.callbacks.values()];
    this.callbacks.clear();
    callbacks.forEach((callback) => callback());
  }
}

describe('ChatStore', () => {
  let client: FakeClient;
  let frames: ManualFrames;
  let store: ChatStore;

  beforeEach(() => {
    client = new FakeClient();
    frames = new ManualFrames();
    TestBed.configureTestingModule({
      providers: [
        ChatStore,
        { provide: ChatStreamClient, useValue: client },
        { provide: FRAME_SCHEDULER, useValue: frames.schedule },
      ],
    });
    store = TestBed.inject(ChatStore);
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  });

  function send(text: string): void {
    store.draft.set(text);
    store.send();
  }

  function assistant(): ChatMessage {
    return store.messages().filter((message) => message.author === 'assistant').at(-1) as ChatMessage;
  }

  describe('sending', () => {
    it('adds the user message and a streaming assistant message at once', () => {
      send('hola');

      const [user, bot] = store.messages();
      expect(user).toMatchObject({ author: 'user', content: 'hola', status: 'complete' });
      expect(bot).toMatchObject({ author: 'assistant', content: '', status: 'streaming' });
      expect(client.requests.map((r) => r.message)).toEqual(['hola']);
    });

    it('clears the draft and reports streaming until the answer ends', () => {
      send('hola');

      expect(store.draft()).toBe('');
      expect(store.isStreaming()).toBe(true);

      client.last.events.next({ type: 'done', fragments: 0 });

      expect(store.isStreaming()).toBe(false);
    });

    it('gives every message a unique id', () => {
      send('uno');
      client.last.events.next({ type: 'done', fragments: 0 });
      send('dos');

      const ids = store.messages().map((message) => message.id);
      expect(new Set(ids).size).toBe(ids.length);
    });

    it('keeps the text exactly as typed, including inner spaces', () => {
      send('  hola   mundo\n');

      expect(client.last.message).toBe('  hola   mundo\n');
    });
  });

  describe('streaming', () => {
    it('accumulates the tokens in order and completes on done', () => {
      send('hola');
      client.last.events.next({ type: 'token', content: 'Ho' });
      client.last.events.next({ type: 'token', content: 'la' });
      client.last.events.next({ type: 'done', fragments: 2 });

      expect(assistant()).toMatchObject({ content: 'Hola', status: 'complete' });
    });

    it('groups the tokens of one frame into a single update', () => {
      send('hola');
      const before = store.messages();

      client.last.events.next({ type: 'token', content: 'a' });
      client.last.events.next({ type: 'token', content: 'b' });
      client.last.events.next({ type: 'token', content: 'c' });

      expect(store.messages()).toBe(before);
      expect(frames.pending).toBe(1);

      frames.run();

      expect(assistant().content).toBe('abc');
      expect(store.messages()).not.toBe(before);
    });

    it('flushes the pending text before completing', () => {
      send('hola');
      client.last.events.next({ type: 'token', content: 'Hola' });
      client.last.events.next({ type: 'done', fragments: 1 });

      expect(assistant()).toMatchObject({ content: 'Hola', status: 'complete' });
      expect(frames.pending).toBe(0);
    });

    it('completes an empty answer without content', () => {
      send('hola');
      client.last.events.next({ type: 'done', fragments: 0 });

      expect(assistant()).toMatchObject({ content: '', status: 'complete' });
    });
  });

  describe('stopping', () => {
    it('cancels the request keeping the received text', () => {
      send('hola');
      client.last.events.next({ type: 'token', content: 'Hol' });

      store.stop();

      expect(client.last.unsubscribed).toBe(true);
      expect(assistant()).toMatchObject({ content: 'Hol', status: 'stopped' });
      expect(assistant().error).toBeUndefined();
      expect(store.isStreaming()).toBe(false);
    });

    it('ignores late events of the stopped request', () => {
      send('hola');
      const stopped = client.last;
      store.stop();
      stopped.events.next({ type: 'token', content: 'tarde' });
      frames.run();

      expect(assistant().content).toBe('');
    });

    it('does not mix a stopped answer with the next one', () => {
      send('uno');
      client.last.events.next({ type: 'token', content: 'vieja' });
      store.stop();

      send('dos');
      client.last.events.next({ type: 'token', content: 'nueva' });
      frames.run();

      const bots = store.messages().filter((message) => message.author === 'assistant');
      expect(bots.map((bot) => bot.content)).toEqual(['vieja', 'nueva']);
    });

    it('does nothing when there is no answer in progress', () => {
      store.stop();
      send('hola');
      client.last.events.next({ type: 'done', fragments: 0 });
      store.stop();

      expect(assistant().status).toBe('complete');
      expect(store.messages()).toHaveLength(2);
    });

    it('cancels the open connection when the store is destroyed', () => {
      send('hola');

      TestBed.resetTestingModule();

      expect(client.last.unsubscribed).toBe(true);
    });
  });

  describe('errors', () => {
    it.each(['network', 'validation', 'unavailable', 'server'] as const)(
      'marks the answer as failed on a %s error before the stream',
      (kind) => {
        send('hola');

        client.last.events.error(new ChatStreamError(kind, 500));

        expect(assistant()).toMatchObject({ status: 'failed', error: kind });
        expect(store.isStreaming()).toBe(false);
      },
    );

    it('keeps the partial text when the stream sends an error event', () => {
      send('hola');
      client.last.events.next({ type: 'token', content: 'Ho' });
      client.last.events.next({ type: 'error', code: 'SLHD_CHAT_00003', message: 'boom' });

      expect(assistant()).toMatchObject({ content: 'Ho', status: 'failed', error: 'stream' });
    });

    it('treats an unexpected failure as a stream error', () => {
      send('hola');

      client.last.events.error(new Error('boom'));

      expect(assistant()).toMatchObject({ status: 'failed', error: 'stream' });
    });

    it('logs only the error kind, never the message text', () => {
      send('secreto del cliente');

      client.last.events.error(new ChatStreamError('network'));

      expect(JSON.stringify(vi.mocked(console.warn).mock.calls)).not.toContain('secreto');
    });

    it('lets the user send again after each kind of failure', () => {
      send('uno');
      client.last.events.error(new ChatStreamError('unavailable', 503));

      store.draft.set('dos');

      expect(store.canSend()).toBe(true);
    });
  });

  describe('retry', () => {
    it('resends the last user message with a new assistant message', () => {
      send('hola');
      client.last.events.next({ type: 'token', content: 'Ho' });
      client.last.events.error(new ChatStreamError('stream'));

      store.retry();

      expect(client.requests.map((r) => r.message)).toEqual(['hola', 'hola']);
      const messages = store.messages();
      expect(messages.filter((m) => m.author === 'user')).toHaveLength(1);
      expect(messages).toHaveLength(3);
      expect(messages[1]).toMatchObject({ status: 'failed', content: 'Ho' });
      expect(messages[2]).toMatchObject({ author: 'assistant', content: '', status: 'streaming' });
    });

    it('does nothing without a previous message or while streaming', () => {
      store.retry();
      expect(client.requests).toHaveLength(0);

      send('hola');
      store.retry();

      expect(client.requests).toHaveLength(1);
    });
  });

  describe('canSend', () => {
    it.each(['', '   ', '\n\t '])('rejects the empty draft %j', (draft) => {
      store.draft.set(draft);

      expect(store.canSend()).toBe(false);
      store.send();
      expect(client.requests).toHaveLength(0);
      expect(store.messages()).toHaveLength(0);
    });

    it('accepts exactly the maximum length and rejects one more', () => {
      store.draft.set('a'.repeat(MAX_MESSAGE_LENGTH));
      expect(store.canSend()).toBe(true);

      store.draft.set('a'.repeat(MAX_MESSAGE_LENGTH + 1));
      expect(store.canSend()).toBe(false);
      store.send();
      expect(client.requests).toHaveLength(0);
    });

    it('blocks sending while an answer is in progress', () => {
      send('uno');
      store.draft.set('dos');

      expect(store.canSend()).toBe(false);
      store.send();

      expect(client.requests).toHaveLength(1);
      expect(store.draft()).toBe('dos');
    });

    it('sends only once when send is called twice in a row', () => {
      store.draft.set('hola');

      store.send();
      store.send();

      expect(client.requests).toHaveLength(1);
      expect(store.messages()).toHaveLength(2);
    });
  });
});
