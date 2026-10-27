import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, Subject } from 'rxjs';
import { Chat } from './chat';
import { ChatStreamError } from './chat-error';
import { ChatStreamEvent } from './chat-message';
import { FRAME_SCHEDULER } from './chat-store';
import { ChatStreamClient } from './chat-stream.client';

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

describe('Chat', () => {
  let client: FakeClient;
  let fixture: ComponentFixture<Chat>;
  let dom: HTMLElement;
  let pendingFrames: (() => void)[];

  beforeEach(async () => {
    client = new FakeClient();
    pendingFrames = [];
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    TestBed.configureTestingModule({
      providers: [
        { provide: ChatStreamClient, useValue: client },
        {
          provide: FRAME_SCHEDULER,
          useValue: (callback: () => void) => {
            pendingFrames.push(callback);
            return () => undefined;
          },
        },
      ],
    });
    fixture = TestBed.createComponent(Chat);
    dom = fixture.nativeElement as HTMLElement;
    document.body.appendChild(dom);
    await fixture.whenStable();
  });

  afterEach(() => dom.remove());

  const textarea = () => dom.querySelector('textarea') as HTMLTextAreaElement;
  const bubbles = () => Array.from(dom.querySelectorAll('app-message-bubble'));
  const button = (label: string) =>
    dom.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement | null;
  const history = () => dom.querySelector('.history') as HTMLElement;

  async function type(text: string): Promise<void> {
    textarea().value = text;
    textarea().dispatchEvent(new Event('input'));
    await fixture.whenStable();
  }

  async function pressEnter(): Promise<KeyboardEvent> {
    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
    textarea().dispatchEvent(event);
    await fixture.whenStable();
    return event;
  }

  async function emit(event: ChatStreamEvent): Promise<void> {
    client.last.events.next(event);
    pendingFrames.splice(0).forEach((callback) => callback());
    await fixture.whenStable();
  }

  async function send(text: string): Promise<void> {
    await type(text);
    await pressEnter();
  }

  describe('empty screen', () => {
    it('invites the user to write and shows the input', () => {
      expect(dom.querySelector('.empty')?.textContent).toContain('Escribe un mensaje');
      expect(bubbles()).toHaveLength(0);
      expect(textarea()).not.toBeNull();
      expect(button('Enviar mensaje')?.disabled).toBe(true);
    });
  });

  describe('conversation', () => {
    it('adds both bubbles when a message is sent and clears the field', async () => {
      await send('Hola');

      expect(bubbles()).toHaveLength(2);
      expect(bubbles()[0].textContent).toContain('Hola');
      expect(bubbles()[1].querySelector('[role="status"]')).not.toBeNull();
      expect(textarea().value).toBe('');
      expect(dom.querySelector('.empty')).toBeNull();
      expect(client.requests.map((r) => r.message)).toEqual(['Hola']);
    });

    it('grows the assistant bubble with the fragments and finishes on done', async () => {
      await send('Hola');

      await emit({ type: 'token', content: 'Ho' });
      await emit({ type: 'token', content: 'la **mundo**' });

      expect(bubbles()[1].textContent).toContain('Hola mundo');
      expect(bubbles()[1].querySelector('strong')?.textContent).toBe('mundo');
      expect(bubbles()[1].querySelector('[role="status"]')).not.toBeNull();

      await emit({ type: 'done', fragments: 2 });

      expect(bubbles()[1].querySelector('[role="status"]')).toBeNull();
    });

    it('returns the focus to the field after sending', async () => {
      await type('Hola');
      button('Enviar mensaje')?.focus();

      button('Enviar mensaje')?.click();
      await fixture.whenStable();

      expect(document.activeElement).toBe(textarea());
    });

    it('sends a single message when Enter is pressed twice in a row', async () => {
      await type('Hola');

      await pressEnter();
      await pressEnter();

      expect(client.requests).toHaveLength(1);
      expect(bubbles()).toHaveLength(2);
    });

    it('does not send while an answer is in progress', async () => {
      await send('uno');

      await send('dos');

      expect(client.requests).toHaveLength(1);
    });
  });

  describe('stop button', () => {
    it('shows only during the answer and stops it keeping the text', async () => {
      expect(button('Detener respuesta')).toBeNull();

      await send('Hola');
      await emit({ type: 'token', content: 'Hol' });
      expect(button('Detener respuesta')).not.toBeNull();

      button('Detener respuesta')?.click();
      await fixture.whenStable();

      expect(client.last.unsubscribed).toBe(true);
      expect(button('Detener respuesta')).toBeNull();
      expect(bubbles()[1].textContent).toContain('Hol');
      expect(bubbles()[1].textContent).toContain('Respuesta interrumpida');
      expect(bubbles()[1].querySelector('[role="status"]')).toBeNull();
    });

    it('lets the user write again after stopping', async () => {
      await send('uno');
      button('Detener respuesta')?.click();
      await fixture.whenStable();

      await send('dos');

      expect(client.requests.map((r) => r.message)).toEqual(['uno', 'dos']);
    });

    it('cancels the open connection when the screen is destroyed', async () => {
      await send('Hola');

      fixture.destroy();

      expect(client.last.unsubscribed).toBe(true);
    });
  });

  describe('errors', () => {
    it('shows the alert and retries with a new answer, only on the last failed one', async () => {
      await send('Hola');
      client.last.events.error(new ChatStreamError('unavailable', 503));
      await fixture.whenStable();

      expect(dom.querySelector('[role="alert"]')?.textContent).toContain('no está disponible');
      expect(bubbles()[1].querySelector('[role="status"]')).toBeNull();

      Array.from(dom.querySelectorAll('button'))
        .find((b) => b.textContent?.includes('Reintentar'))
        ?.click();
      await fixture.whenStable();

      expect(client.requests.map((r) => r.message)).toEqual(['Hola', 'Hola']);
      expect(bubbles()).toHaveLength(3);
      expect(dom.textContent).not.toContain('Reintentar');
    });

    it('lets the user send a new message after a failure', async () => {
      await send('Hola');
      client.last.events.error(new ChatStreamError('network'));
      await fixture.whenStable();

      await send('Otra vez');

      expect(client.requests.map((r) => r.message)).toEqual(['Hola', 'Otra vez']);
    });
  });

  describe('following the answer', () => {
    let scrollTop: number;

    beforeEach(() => {
      scrollTop = 0;
      Object.defineProperty(history(), 'scrollHeight', { configurable: true, get: () => 1000 });
      Object.defineProperty(history(), 'clientHeight', { configurable: true, get: () => 400 });
      Object.defineProperty(history(), 'scrollTop', {
        configurable: true,
        get: () => scrollTop,
        set: (value: number) => (scrollTop = value),
      });
    });

    it('keeps the history at the end while fragments arrive', async () => {
      await send('Hola');
      await emit({ type: 'token', content: 'texto' });

      expect(scrollTop).toBe(1000);
      expect(button('Ir al final')).toBeNull();
    });

    it('stops following when the user scrolls up and offers to go back to the end', async () => {
      await send('Hola');
      scrollTop = 100;
      history().dispatchEvent(new Event('scroll'));
      await fixture.whenStable();

      await emit({ type: 'token', content: 'texto' });

      expect(scrollTop).toBe(100);
      expect(button('Ir al final')).not.toBeNull();

      button('Ir al final')?.click();
      await fixture.whenStable();

      expect(scrollTop).toBe(1000);
      expect(button('Ir al final')).toBeNull();

      await emit({ type: 'token', content: ' más' });
      expect(scrollTop).toBe(1000);
    });

    it('follows again when the user scrolls back to the end', async () => {
      scrollTop = 100;
      history().dispatchEvent(new Event('scroll'));
      await fixture.whenStable();
      expect(button('Ir al final')).not.toBeNull();

      scrollTop = 590;
      history().dispatchEvent(new Event('scroll'));
      await fixture.whenStable();

      expect(button('Ir al final')).toBeNull();
    });
  });

  describe('accessibility', () => {
    it('exposes the history as a labelled live log', () => {
      expect(history().getAttribute('role')).toBe('log');
      expect(history().getAttribute('aria-live')).toBe('polite');
      expect(history().getAttribute('aria-label')).toBe('Conversación');
    });

    it('marks the log as busy while an answer is written', async () => {
      expect(history().getAttribute('aria-busy')).toBe('false');

      await send('Hola');
      expect(history().getAttribute('aria-busy')).toBe('true');

      await emit({ type: 'done', fragments: 0 });
      expect(history().getAttribute('aria-busy')).toBe('false');
    });

    it('gives every button an accessible name', async () => {
      await send('Hola');

      const names = Array.from(dom.querySelectorAll('button')).map((b) => b.getAttribute('aria-label') ?? b.textContent?.trim());

      expect(names.every((name) => !!name)).toBe(true);
    });

    it('names the author of each bubble', async () => {
      await send('Hola');

      const labels = Array.from(dom.querySelectorAll('.bubble')).map((b) => b.getAttribute('aria-label'));

      expect(labels).toEqual(['Tú', 'Asistente']);
    });

    it('writes, sends and stops using only the keyboard', async () => {
      textarea().focus();
      await type('Hola');
      const enter = await pressEnter();
      expect(enter.defaultPrevented).toBe(true);
      expect(client.requests).toHaveLength(1);

      const stop = button('Detener respuesta') as HTMLButtonElement;
      stop.focus();
      expect(document.activeElement).toBe(stop);
      stop.click();
      await fixture.whenStable();

      expect(client.last.unsubscribed).toBe(true);
    });
  });
});
