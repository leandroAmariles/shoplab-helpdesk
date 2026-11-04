import { SecurityContext } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DomSanitizer } from '@angular/platform-browser';
import { ChatSource } from '../../features/chat/chat-message';
import { MarkdownRenderer, RenderOptions } from './markdown-renderer';

describe('MarkdownRenderer', () => {
  let renderer: MarkdownRenderer;
  let sanitizer: DomSanitizer;

  beforeEach(() => {
    renderer = TestBed.inject(MarkdownRenderer);
    sanitizer = TestBed.inject(DomSanitizer);
  });

  /** Renders and mounts the result so the tests inspect elements and attributes, not strings. */
  function mount(markdown: string, options?: RenderOptions): HTMLElement {
    const html = sanitizer.sanitize(SecurityContext.HTML, renderer.render(markdown, options)) ?? '';
    const container = document.createElement('div');
    container.innerHTML = html;
    return container;
  }

  describe('malicious content', () => {
    it('removes script elements', () => {
      const dom = mount('<script>alert(1)</script>hola');

      expect(dom.querySelector('script')).toBeNull();
      expect(dom.textContent).not.toContain('alert(1)');
    });

    it('removes images with event handlers', () => {
      const dom = mount('<img src=x onerror=alert(1)>');

      expect(dom.querySelector('img')).toBeNull();
      expect(dom.innerHTML).not.toContain('onerror');
    });

    it('removes javascript: links', () => {
      const link = mount('[clic](javascript:alert(1))').querySelector('a');

      expect(link?.hasAttribute('href')).toBe(false);
    });

    it('removes data: links', () => {
      const link = mount('[clic](data:text/html;base64,PHNjcmlwdD4=)').querySelector('a');

      expect(link?.hasAttribute('href')).toBe(false);
    });

    it('removes iframes', () => {
      expect(mount('<iframe src="https://example.com"></iframe>').querySelector('iframe')).toBeNull();
    });

    it('keeps a link but drops its event handler', () => {
      const link = mount('<a href="https://x.example" onclick="alert(1)">x</a>').querySelector('a');

      expect(link?.getAttribute('href')).toBe('https://x.example');
      expect(link?.hasAttribute('onclick')).toBe(false);
      expect(link?.getAttribute('rel')).toBe('noopener noreferrer');
    });

    it('removes svg elements', () => {
      expect(mount('<svg onload=alert(1)></svg>').querySelector('svg')).toBeNull();
    });

    it('removes style elements', () => {
      expect(mount('<style>body{display:none}</style>').querySelector('style')).toBeNull();
    });

    it('removes forms, inputs and embedded objects', () => {
      const dom = mount('<form action="/x"><input name="a"></form><object data="x"></object><embed src="x">');

      for (const tag of ['form', 'input', 'object', 'embed']) {
        expect(dom.querySelector(tag)).toBeNull();
      }
    });

    it('removes style and srcdoc attributes', () => {
      const dom = mount('<p style="color:red" srcdoc="x">texto</p>');

      expect(dom.querySelector('p')?.hasAttribute('style')).toBe(false);
      expect(dom.querySelector('p')?.hasAttribute('srcdoc')).toBe(false);
    });

    it('does not let raw HTML reuse the classes of the application', () => {
      const dom = mount('<p class="typing">texto</p>');

      expect(dom.querySelector('p')?.hasAttribute('class')).toBe(false);
    });

    it('shows HTML inside code as text', () => {
      const inline = mount('`<script>x</script>`');
      const block = mount('```html\n<script>x</script>\n```');

      expect(inline.querySelector('script')).toBeNull();
      expect(inline.querySelector('code')?.textContent).toBe('<script>x</script>');
      expect(block.querySelector('script')).toBeNull();
      expect(block.querySelector('pre code')?.textContent).toContain('<script>x</script>');
    });

    it('produces a valid DOM from malformed HTML', () => {
      const dom = mount('<p><b>x');

      expect(dom.textContent).toContain('x');
      expect(dom.querySelector('b')).toBeNull();
    });
  });

  describe('links and images', () => {
    it('opens links in a new tab without access to the opener', () => {
      const link = mount('[docs](https://example.com/a)').querySelector('a');

      expect(link?.getAttribute('href')).toBe('https://example.com/a');
      expect(link?.getAttribute('target')).toBe('_blank');
      expect(link?.getAttribute('rel')).toBe('noopener noreferrer');
    });

    it('keeps mailto links', () => {
      expect(mount('[mail](mailto:a@b.co)').querySelector('a')?.getAttribute('href')).toBe('mailto:a@b.co');
    });

    it('never renders remote images: shows the alt text as a link', () => {
      const dom = mount('![logo](https://example.com/a.png)');

      expect(dom.querySelector('img')).toBeNull();
      expect(dom.querySelector('a')?.textContent).toBe('logo');
      expect(dom.querySelector('a')?.getAttribute('href')).toBe('https://example.com/a.png');
    });

    it('escapes the alt text of an image', () => {
      const dom = mount('![<b>x</b>](https://example.com/a.png)');

      expect(dom.querySelector('b')).toBeNull();
    });
  });

  describe('standard Markdown', () => {
    it('renders headings, emphasis and quotes', () => {
      const dom = mount('# Título\n\n**negrita** y *cursiva* y ~~tachado~~\n\n> cita');

      expect(dom.querySelector('h1')?.textContent).toBe('Título');
      expect(dom.querySelector('strong')?.textContent).toBe('negrita');
      expect(dom.querySelector('em')?.textContent).toBe('cursiva');
      expect(dom.querySelector('del')?.textContent).toBe('tachado');
      expect(dom.querySelector('blockquote')).not.toBeNull();
    });

    it('renders ordered and unordered lists', () => {
      const dom = mount('- a\n- b\n\n3. c\n4. d');

      expect(dom.querySelectorAll('ul li')).toHaveLength(2);
      expect(dom.querySelector('ol')?.getAttribute('start')).toBe('3');
      expect(dom.querySelectorAll('ol li')).toHaveLength(2);
    });

    it('renders tables', () => {
      const dom = mount('| a | b |\n|---|---|\n| 1 | 2 |');

      expect(dom.querySelectorAll('th')).toHaveLength(2);
      expect(dom.querySelectorAll('tbody td')).toHaveLength(2);
    });

    it('renders inline code and fenced code with its language', () => {
      const dom = mount('usa `npm test`\n\n```python\nprint(1)\n```');

      expect(dom.querySelector('p code')?.textContent).toBe('npm test');
      expect(dom.querySelector('pre code')?.getAttribute('class')).toBe('language-python');
    });
  });

  describe('incomplete input while streaming', () => {
    it.each(['```python\nprint(1', '**negrita sin cerrar', '| a | b |\n|---|', '- item\n-', '[enlace](https://exa', '<scr'])(
      'renders %j without throwing',
      (partial) => {
        expect(() => mount(partial)).not.toThrow();
      },
    );

    it('renders an open code fence as code until the end', () => {
      const dom = mount('```python\nprint(1)');

      expect(dom.querySelector('pre code')?.textContent).toContain('print(1)');
    });

    it('keeps an unclosed emphasis as literal text', () => {
      expect(mount('**negrita').textContent).toContain('**negrita');
    });

    it('returns an empty result for an empty string', () => {
      expect(mount('').innerHTML).toBe('');
    });

    it('gives the same result for the same text every time', () => {
      const text = '# Hola\n\n- a\n- b\n\n```ts\nconst x = 1;\n```';
      mount(text.slice(0, 7));
      mount(text.slice(0, 20));

      expect(mount(text).innerHTML).toBe(mount(text).innerHTML);
    });
  });

  describe('citations', () => {
    const sources: ChatSource[] = [
      { number: 1, title: 'Política de devoluciones', section: 'Plazos', text: 'Tienes 30 días.' },
      { number: 2, title: 'Reembolsos', section: '', text: 'Diez días.' },
      { number: 3, title: 'Envíos', section: '', text: 'Cinco días.' },
      { number: 4, title: 'Garantía', section: '', text: 'Un año.' },
    ];

    function buttons(dom: HTMLElement): HTMLButtonElement[] {
      return [...dom.querySelectorAll<HTMLButtonElement>('button.citation')];
    }

    it('turns a valid marker into an accessible button', () => {
      const [button] = buttons(mount('Tienes 30 días [1].', { sources }));

      expect(button.type).toBe('button');
      expect(button.dataset['source']).toBe('1');
      expect(button.textContent).toBe('[1]');
      expect(button.getAttribute('aria-label')).toBe('Fuente 1: Política de devoluciones');
    });

    it('leaves the text around the marker alone', () => {
      const dom = mount('Tienes 30 días [1].', { sources });

      expect(dom.textContent?.trim()).toBe('Tienes 30 días [1].');
    });

    it('makes one button per number of a list or a range', () => {
      expect(buttons(mount('A [1, 2]', { sources })).map((b) => b.dataset['source'])).toEqual(['1', '2']);
      expect(buttons(mount('A [1-3]', { sources })).map((b) => b.dataset['source'])).toEqual(['1', '2', '3']);
      expect(buttons(mount('A [1][2]', { sources })).map((b) => b.dataset['source'])).toEqual(['1', '2']);
    });

    it('keeps a number without a source as plain text', () => {
      const dom = mount('Dato [7].', { sources });

      expect(buttons(dom)).toEqual([]);
      expect(dom.textContent?.trim()).toBe('Dato [7].');
    });

    it('turns only the valid numbers of a mixed list into buttons', () => {
      const dom = mount('Dato [1, 9].', { sources });

      expect(buttons(dom).map((b) => b.dataset['source'])).toEqual(['1']);
      expect(dom.textContent).toContain('[9]');
    });

    it.each(['[0]', '[]', '[abc]', '[1a]', '[2024]', '(1)', '【1】', '[opcional]', '[3-1]'])(
      'leaves %s as text',
      (marker) => {
        const dom = mount(`Dato ${marker}.`, { sources });

        expect(buttons(dom)).toEqual([]);
        expect(dom.textContent?.trim()).toBe(`Dato ${marker}.`);
      },
    );

    it('does nothing without sources', () => {
      expect(buttons(mount('Dato [1].'))).toEqual([]);
      expect(buttons(mount('Dato [1].', { sources: [] }))).toEqual([]);
    });

    it('does not turn markers inside code into buttons', () => {
      const dom = mount('En línea `[1]` y\n\n```\n[2]\n```', { sources });

      expect(buttons(dom)).toEqual([]);
      expect(dom.querySelector('code')?.textContent).toBe('[1]');
    });

    it('does not put a button inside a link', () => {
      const dom = mount('[ver [1]](https://example.com/a)', { sources });

      expect(buttons(dom)).toEqual([]);
      expect(dom.querySelector('a')?.getAttribute('href')).toBe('https://example.com/a');
    });

    it('keeps [1](url) as an ordinary link', () => {
      const dom = mount('[1](https://example.com/a)', { sources });

      expect(buttons(dom)).toEqual([]);
      expect(dom.querySelector('a')?.textContent).toBe('1');
    });

    it('works inside emphasis, lists and tables', () => {
      expect(buttons(mount('**dato [1]**', { sources })).length).toBe(1);
      expect(buttons(mount('- uno [1]\n- dos [2]', { sources })).length).toBe(2);
      expect(buttons(mount('| a |\n|---|\n| x [3] |', { sources })).length).toBe(1);
    });

    it('escapes the title in the accessible name', () => {
      const hostile: ChatSource[] = [
        { number: 1, title: '"><img src=x onerror=alert(1)>', section: '', text: 'x' },
      ];

      const dom = mount('Dato [1]', { sources: hostile });

      expect(dom.querySelector('img')).toBeNull();
      expect(buttons(dom)[0].getAttribute('aria-label')).toBe('Fuente 1: "><img src=x onerror=alert(1)>');
    });

    it('removes buttons written by the model, even if they imitate a citation', () => {
      const dom = mount('<button class="citation" data-source="1" onclick="alert(1)">falsa</button> y <button>x</button>', {
        sources,
      });

      expect(dom.querySelector('button')).toBeNull();
      expect(dom.hasAttribute('onclick')).toBe(false);
    });

    it('does not leave internal attributes on the buttons', () => {
      const [button] = buttons(mount('Dato [1]', { sources }));

      expect(button.getAttributeNames().sort()).toEqual(['aria-label', 'class', 'data-source', 'type']);
    });

    it.each(['Dato [', 'Dato [1', 'Dato [1,', 'Dato [1-', 'Dato [1, 2'])(
      'hides the unfinished marker %j while the answer is streaming',
      (text) => {
        const dom = mount(text, { sources, streaming: true });

        expect(dom.textContent?.trim()).toBe('Dato');
      },
    );

    it('shows the same text as a normal answer once it is not streaming', () => {
      expect(mount('Dato [1', { sources, streaming: false }).textContent?.trim()).toBe('Dato [1');
    });

    it('does not hide ordinary brackets that are already closed or are not digits', () => {
      expect(mount('Dato [ver', { sources, streaming: true }).textContent?.trim()).toBe('Dato [ver');
      expect(buttons(mount('Dato [1]', { sources, streaming: true })).length).toBe(1);
    });
  });
});
