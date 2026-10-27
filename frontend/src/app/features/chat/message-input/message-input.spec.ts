import { ComponentFixture, TestBed } from '@angular/core/testing';
import { COUNTER_THRESHOLD, MAX_MESSAGE_LENGTH } from '../chat-message';
import { MessageInput } from './message-input';

describe('MessageInput', () => {
  let fixture: ComponentFixture<MessageInput>;
  let dom: HTMLElement;
  let sent: ReturnType<typeof vi.fn<() => void>>;
  let stopped: ReturnType<typeof vi.fn<() => void>>;

  async function create(inputs: { draft?: string; canSend?: boolean; streaming?: boolean } = {}) {
    fixture = TestBed.createComponent(MessageInput);
    fixture.componentRef.setInput('draft', inputs.draft ?? '');
    fixture.componentRef.setInput('canSend', inputs.canSend ?? false);
    fixture.componentRef.setInput('streaming', inputs.streaming ?? false);
    sent = vi.fn<() => void>();
    stopped = vi.fn<() => void>();
    fixture.componentInstance.send.subscribe(sent);
    fixture.componentInstance.stop.subscribe(stopped);
    await fixture.whenStable();
    dom = fixture.nativeElement as HTMLElement;
  }

  const textarea = () => dom.querySelector('textarea') as HTMLTextAreaElement;
  const button = (label: string) =>
    dom.querySelector(`button[aria-label="${label}"]`) as HTMLButtonElement | null;

  function press(key: string, init: KeyboardEventInit = {}): KeyboardEvent {
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
    textarea().dispatchEvent(event);
    return event;
  }

  describe('stop button', () => {
    it('is shown only while an answer is in progress', async () => {
      await create({ streaming: true });
      expect(button('Detener respuesta')).not.toBeNull();
      expect(button('Enviar mensaje')).toBeNull();

      await create({ streaming: false });
      expect(button('Detener respuesta')).toBeNull();
      expect(button('Enviar mensaje')).not.toBeNull();
    });

    it('emits stop when pressed', async () => {
      await create({ streaming: true });

      button('Detener respuesta')?.click();

      expect(stopped).toHaveBeenCalledTimes(1);
    });
  });

  describe('sending', () => {
    it('disables the send button when the message cannot be sent', async () => {
      await create({ canSend: false });
      expect(button('Enviar mensaje')?.disabled).toBe(true);

      await create({ canSend: true });
      expect(button('Enviar mensaje')?.disabled).toBe(false);
    });

    it('sends on Enter and prevents the line break', async () => {
      await create({ draft: 'hola', canSend: true });

      const event = press('Enter');

      expect(sent).toHaveBeenCalledTimes(1);
      expect(event.defaultPrevented).toBe(true);
    });

    it('does not send on Shift+Enter, so the line break is kept', async () => {
      await create({ draft: 'hola', canSend: true });

      const event = press('Enter', { shiftKey: true });

      expect(sent).not.toHaveBeenCalled();
      expect(event.defaultPrevented).toBe(false);
    });

    it('does not send on Enter while composing text with an IME', async () => {
      await create({ draft: 'hola', canSend: true });

      press('Enter', { isComposing: true });

      expect(sent).not.toHaveBeenCalled();
    });

    it('does not send on Enter when sending is not allowed', async () => {
      await create({ draft: '   ', canSend: false });

      const event = press('Enter');

      expect(sent).not.toHaveBeenCalled();
      expect(event.defaultPrevented).toBe(true);
    });

    it('sends when the form is submitted with the button', async () => {
      await create({ draft: 'hola', canSend: true });

      button('Enviar mensaje')?.click();

      expect(sent).toHaveBeenCalledTimes(1);
    });
  });

  describe('draft', () => {
    it('shows the draft and publishes what the user types', async () => {
      await create({ draft: 'hola' });
      expect(textarea().value).toBe('hola');

      textarea().value = 'hola mundo';
      textarea().dispatchEvent(new Event('input'));

      expect(fixture.componentInstance.draft()).toBe('hola mundo');
    });

    it('moves the focus to the field on demand', async () => {
      await create();
      document.body.appendChild(dom);

      fixture.componentInstance.focus();

      expect(document.activeElement).toBe(textarea());
      dom.remove();
    });
  });

  describe('counter', () => {
    it('is hidden below the threshold', async () => {
      await create({ draft: 'a'.repeat(COUNTER_THRESHOLD - 1) });

      expect(dom.querySelector('mat-hint')?.textContent).not.toContain(`/ ${MAX_MESSAGE_LENGTH}`);
    });

    it('shows the length from the threshold on, without an error', async () => {
      await create({ draft: 'a'.repeat(COUNTER_THRESHOLD) });

      const hint = dom.querySelector('mat-hint') as HTMLElement;
      expect(hint.textContent).toContain(`${COUNTER_THRESHOLD} / ${MAX_MESSAGE_LENGTH}`);
      expect(hint.classList).not.toContain('over');
    });

    it('does not flag the maximum length itself', async () => {
      await create({ draft: 'a'.repeat(MAX_MESSAGE_LENGTH) });

      expect((dom.querySelector('mat-hint') as HTMLElement).classList).not.toContain('over');
    });

    it('flags the length above the maximum and explains the limit', async () => {
      await create({ draft: 'a'.repeat(MAX_MESSAGE_LENGTH + 1) });

      const hint = dom.querySelector('mat-hint') as HTMLElement;
      expect(hint.classList).toContain('over');
      expect(hint.textContent).toContain(`Máximo ${MAX_MESSAGE_LENGTH} caracteres`);
    });
  });
});
