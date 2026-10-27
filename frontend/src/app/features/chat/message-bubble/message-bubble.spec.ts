import { ComponentFixture, TestBed } from '@angular/core/testing';
import { toUserMessage } from '../chat-error';
import { ChatMessage } from '../chat-message';
import { MessageBubble } from './message-bubble';

describe('MessageBubble', () => {
  let fixture: ComponentFixture<MessageBubble>;

  async function render(message: Partial<ChatMessage>, canRetry = false): Promise<HTMLElement> {
    fixture = TestBed.createComponent(MessageBubble);
    fixture.componentRef.setInput('message', {
      id: 'm1',
      author: 'assistant',
      content: '',
      status: 'complete',
      ...message,
    });
    fixture.componentRef.setInput('canRetry', canRetry);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  describe('author', () => {
    it('styles the user and the assistant differently', async () => {
      const user = await render({ author: 'user', content: 'hola' });
      expect(user.querySelector('.bubble')?.classList).toContain('user');

      const bot = await render({ author: 'assistant', content: 'hola' });
      expect(bot.querySelector('.bubble')?.classList).toContain('assistant');
    });

    it('names the author for screen readers', async () => {
      const user = await render({ author: 'user', content: 'hola' });
      expect(user.querySelector('.bubble')?.getAttribute('aria-label')).toBe('Tú');

      const bot = await render({ author: 'assistant', content: 'hola' });
      expect(bot.querySelector('.bubble')?.getAttribute('aria-label')).toBe('Asistente');
    });

    it('shows the user text literally, without interpreting Markdown or HTML', async () => {
      const dom = await render({ author: 'user', content: '<b>x</b> **y** `z`' });

      expect(dom.querySelector('.text')?.textContent).toBe('<b>x</b> **y** `z`');
      expect(dom.querySelector('b')).toBeNull();
      expect(dom.querySelector('strong')).toBeNull();
      expect(dom.querySelector('code')).toBeNull();
    });
  });

  describe('typing indicator', () => {
    it('shows while streaming, even before the first fragment', async () => {
      const dom = await render({ status: 'streaming', content: '' });

      const indicator = dom.querySelector('[role="status"]');
      expect(indicator).not.toBeNull();
      expect(indicator?.getAttribute('aria-label')).toContain('escribiendo');
    });

    it('shows next to the text received so far', async () => {
      const dom = await render({ status: 'streaming', content: 'Hola' });

      expect(dom.querySelector('.markdown')?.textContent).toContain('Hola');
      expect(dom.querySelector('[role="status"]')).not.toBeNull();
    });

    it.each(['complete', 'stopped', 'failed'] as const)('is hidden when the answer is %s', async (status) => {
      const dom = await render({ status, content: 'Hola', error: status === 'failed' ? 'stream' : undefined });

      expect(dom.querySelector('[role="status"]')).toBeNull();
    });
  });

  describe('empty answer', () => {
    it('says there was no answer when it completes without content', async () => {
      const dom = await render({ status: 'complete', content: '' });

      expect(dom.querySelector('.note')?.textContent).toContain('Sin respuesta');
      expect(dom.querySelector('[role="status"]')).toBeNull();
      expect(dom.querySelector('[role="alert"]')).toBeNull();
    });

    it('does not say it while still streaming', async () => {
      const dom = await render({ status: 'streaming', content: '' });

      expect(dom.textContent).not.toContain('Sin respuesta');
    });
  });

  describe('stopped answer', () => {
    it('keeps the text and marks it as interrupted without an error style', async () => {
      const dom = await render({ status: 'stopped', content: 'Hol' });

      expect(dom.querySelector('.markdown')?.textContent).toContain('Hol');
      expect(dom.querySelector('.note')?.textContent).toContain('Respuesta interrumpida');
      expect(dom.querySelector('[role="alert"]')).toBeNull();
    });
  });

  describe('Markdown', () => {
    it('renders lists and fenced code of the assistant', async () => {
      const dom = await render({ content: '- uno\n- dos\n\n```ts\nconst x = 1;\n```' });

      expect(dom.querySelectorAll('.markdown ul li')).toHaveLength(2);
      expect(dom.querySelector('.markdown pre code')?.textContent).toContain('const x = 1;');
    });

    it('does not insert scripts or event handlers of the assistant', async () => {
      const dom = await render({
        content: 'hola <script>alert(1)</script><img src=x onerror=alert(1)> [x](javascript:alert(1))',
      });

      expect(dom.querySelector('script')).toBeNull();
      expect(dom.querySelector('img')).toBeNull();
      expect(dom.innerHTML).not.toContain('onerror');
      expect(dom.querySelector('.markdown a')?.hasAttribute('href')).toBe(false);
    });

    it('stays valid while the answer is still being written', async () => {
      const dom = await render({ status: 'streaming', content: '```python\nprint(1' });

      expect(dom.querySelector('.markdown pre code')?.textContent).toContain('print(1');
    });

    it('renders the text received so far the same way as the complete answer', async () => {
      const full = '# Título\n\n- a\n- b\n\n**fin**';
      const whole = (await render({ content: full })).querySelector('.markdown')?.innerHTML;

      for (const end of [3, 11, 20]) {
        await render({ status: 'streaming', content: full.slice(0, end) });
      }
      const afterPrefixes = (await render({ content: full })).querySelector('.markdown')?.innerHTML;

      expect(afterPrefixes).toBe(whole);
    });
  });

  describe('failed answer', () => {
    it('keeps the partial text and shows a Spanish alert without technical details', async () => {
      const dom = await render({ status: 'failed', content: 'Ho', error: 'unavailable' });

      expect(dom.querySelector('.markdown')?.textContent).toContain('Ho');
      const alert = dom.querySelector('[role="alert"]');
      expect(alert?.textContent).toContain(toUserMessage('unavailable'));
      expect(alert?.textContent).not.toMatch(/SLHD|503/);
    });

    it('offers to retry only when it is the last answer', async () => {
      const without = await render({ status: 'failed', error: 'network' }, false);
      expect(without.querySelector('button')).toBeNull();

      const withRetry = await render({ status: 'failed', error: 'network' }, true);
      expect(withRetry.querySelector('button')?.textContent).toContain('Reintentar');
    });

    it('emits retry when the button is pressed', async () => {
      const dom = await render({ status: 'failed', error: 'network' }, true);
      const retried = vi.fn();
      fixture.componentInstance.retry.subscribe(retried);

      dom.querySelector('button')?.click();

      expect(retried).toHaveBeenCalledTimes(1);
    });
  });
});
