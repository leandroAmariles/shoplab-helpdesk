import { ComponentFixture, TestBed } from '@angular/core/testing';
import { toUserMessage } from '../chat-error';
import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { ChatMessage, ChatSource } from '../chat-message';
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
      sources: [],
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

  describe('sources', () => {
    const sources: ChatSource[] = [
      { number: 1, title: 'Política de devoluciones', section: 'Plazos', text: 'Tienes 30 días.' },
      { number: 2, title: 'Reembolsos', section: '', text: 'Diez días.' },
      { number: 3, title: 'Envíos', section: '', text: 'Cinco días.' },
    ];

    function entries(dom: HTMLElement): (string | undefined)[] {
      return [...dom.querySelectorAll('app-source-list button.source')].map((button) =>
        button.textContent?.replace(/\s+/g, ' ').trim(),
      );
    }

    it('turns the valid citations of the text into buttons', async () => {
      const dom = await render({ content: 'Plazo de 30 días [1].', sources });

      const button = dom.querySelector<HTMLButtonElement>('.markdown button.citation');
      expect(button?.dataset['source']).toBe('1');
      expect(button?.getAttribute('aria-label')).toBe('Fuente 1: Política de devoluciones');
    });

    it('lists the cited sources under the answer', async () => {
      const dom = await render({ content: 'Plazo [1] y envío [3].', sources });

      expect(entries(dom)).toEqual(['1. Política de devoluciones › Plazos', '3. Envíos']);
      expect(dom.querySelector('app-source-list section')?.getAttribute('aria-label')).toBe('Fuentes');
    });

    it('lists a source once however many times it is cited', async () => {
      const dom = await render({ content: 'A [1]. B [1]. C [1, 2].', sources });

      expect(entries(dom)).toEqual(['1. Política de devoluciones › Plazos', '2. Reembolsos']);
    });

    it('keeps the numbering of each answer apart', async () => {
      const first = await render({ content: 'Dato [1]', sources });
      const second = await render({ content: 'Dato [1]', sources: [{ ...sources[1], number: 1 }] });

      expect(entries(first)).toEqual(['1. Política de devoluciones › Plazos']);
      expect(entries(second)).toEqual(['1. Reembolsos']);
    });

    it('shows no list without sources, in the user message or without citations yet', async () => {
      expect((await render({ content: 'Hola', sources: [] })).querySelector('app-source-list')).toBeNull();
      expect(
        (await render({ author: 'user', content: 'Hola [1]', sources })).querySelector('app-source-list section'),
      ).toBeNull();
      expect(
        (await render({ status: 'streaming', content: 'Hola', sources })).querySelector('app-source-list section'),
      ).toBeNull();
    });

    it('does not hide the answer text when the sources are there', async () => {
      const dom = await render({ content: 'Plazo [1].', sources });

      expect(dom.querySelector('.markdown')?.textContent).toContain('Plazo [1].');
    });
  });

  describe('opening a fragment', () => {
    const sources: ChatSource[] = [
      { number: 1, title: 'Política de devoluciones', section: 'Plazos', text: 'Tienes 30 días.' },
      { number: 2, title: 'Reembolsos', section: '', text: 'Diez días.' },
    ];
    let dialog: MatDialog;

    function container(): HTMLElement | null {
      return document.querySelector('mat-dialog-container');
    }

    async function opened(): Promise<void> {
      await firstValueFrom(dialog.openDialogs[0].afterOpened());
    }

    beforeEach(() => {
      dialog = TestBed.inject(MatDialog);
    });

    afterEach(() => {
      dialog.closeAll();
    });

    it('opens the fragment of a citation of the text when it is clicked', async () => {
      const dom = await render({ content: 'Plazo [1] y reembolso [2].', sources });

      dom.querySelectorAll<HTMLButtonElement>('.markdown button.citation')[1].click();
      await opened();

      expect(container()?.textContent).toContain('Reembolsos');
      expect(container()?.textContent).toContain('Diez días.');
    });

    it('opens the same fragment from the entry of the list', async () => {
      const dom = await render({ content: 'Plazo [1].', sources });

      dom.querySelector<HTMLButtonElement>('app-source-list button.source')?.click();
      await opened();

      expect(container()?.textContent).toContain('Política de devoluciones');
      expect(container()?.textContent).toContain('Plazos');
      expect(container()?.textContent).toContain('Tienes 30 días.');
    });

    it('opens a citation that arrived while the answer is still being written', async () => {
      const dom = await render({ status: 'streaming', content: 'Plazo [1]', sources });

      dom.querySelector<HTMLButtonElement>('.markdown button.citation')?.click();
      await opened();

      expect(container()?.textContent).toContain('Tienes 30 días.');
    });

    it('keeps a single dialog when another fragment is opened', async () => {
      const dom = await render({ content: 'A [1] y B [2].', sources });
      const [first, second] = dom.querySelectorAll<HTMLButtonElement>('.markdown button.citation');

      first.click();
      await opened();
      second.click();

      await vi.waitFor(() => {
        expect(dialog.openDialogs).toHaveLength(1);
        expect(container()?.textContent).toContain('Diez días.');
      });
      expect(document.querySelectorAll('mat-dialog-container')).toHaveLength(1);
    });

    it('closes with Escape and gives the focus back to the citation', async () => {
      const dom = await render({ content: 'Plazo [1].', sources });
      const citation = dom.querySelector<HTMLButtonElement>('.markdown button.citation') as HTMLButtonElement;
      citation.focus();

      citation.click();
      await opened();
      const closed = firstValueFrom(dialog.openDialogs[0].afterClosed());
      document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true }));
      await closed;

      expect(dialog.openDialogs).toHaveLength(0);
      expect(document.activeElement).toBe(citation);
    });

    it('ignores a citation button that has no matching source', async () => {
      const dom = await render({ content: 'Plazo [1].', sources });
      const citation = dom.querySelector<HTMLButtonElement>('.markdown button.citation') as HTMLButtonElement;
      citation.dataset['source'] = '9';

      citation.click();
      await fixture.whenStable();

      expect(dialog.openDialogs).toHaveLength(0);
    });
  });

  describe('coherent sources', () => {
    const sources: ChatSource[] = [1, 2, 3, 4].map((number) => ({
      number,
      title: `Documento ${number}`,
      section: '',
      text: `Texto ${number}`,
    }));

    function listed(dom: HTMLElement): (string | null | undefined)[] {
      return [...dom.querySelectorAll('app-source-list button.source')].map((b) => b.getAttribute('aria-label'));
    }

    function label(dom: HTMLElement): string | undefined {
      return dom.querySelector('app-source-list h3')?.textContent?.trim();
    }

    it('lists only the sources the text cites', async () => {
      const dom = await render({ content: 'Uno [1] y tres [3].', sources });

      expect(listed(dom)).toEqual(['Fuente 1: Documento 1', 'Fuente 3: Documento 3']);
      expect(label(dom)).toBe('Fuentes');
    });

    it.each(['complete', 'stopped'] as const)(
      'shows the sources consulted, under their own label, when a %s answer cites none',
      async (status) => {
        const dom = await render({ status, content: 'Tienes 30 días para devolver.', sources });

        expect(label(dom)).toBe('Fuentes consultadas');
        expect(listed(dom)).toHaveLength(4);
        expect(dom.querySelector('.markdown button.citation')).toBeNull();
      },
    );

    it('does not show the consulted sources while the answer is still being written', async () => {
      const dom = await render({ status: 'streaming', content: 'Tienes 30 días', sources });

      expect(dom.querySelector('app-source-list')).toBeNull();
    });

    it('does not show the consulted sources when the answer failed without citing', async () => {
      const dom = await render({ status: 'failed', error: 'stream', content: 'Tienes', sources });

      expect(dom.querySelector('app-source-list')).toBeNull();
    });

    it('keeps the citations already written when the answer failed', async () => {
      const dom = await render({ status: 'failed', error: 'stream', content: 'Uno [2] y', sources });

      expect(listed(dom)).toEqual(['Fuente 2: Documento 2']);
      expect(label(dom)).toBe('Fuentes');
    });

    it('does not present a number without a source as a citation or as a source', async () => {
      const dom = await render({ content: 'Dato [9] y [1, 9].', sources });

      expect([...dom.querySelectorAll('.markdown button.citation')].map((b) => b.textContent)).toEqual(['[1]']);
      expect(listed(dom)).toEqual(['Fuente 1: Documento 1']);
      expect(dom.querySelector('.markdown')?.textContent).toContain('[9]');
    });

    it('leaves bracketed text that is not a citation alone', async () => {
      const dom = await render({ content: 'Pago [opcional] en [2024].', sources });

      expect(dom.querySelector('.markdown button')).toBeNull();
      expect(label(dom)).toBe('Fuentes consultadas');
      expect(dom.querySelector('.markdown')?.textContent).toContain('[opcional]');
    });
  });
});
