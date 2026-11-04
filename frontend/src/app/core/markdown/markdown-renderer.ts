import { inject, Injectable } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import DOMPurify from 'dompurify';
import { Marked, TokenizerThis, Tokens } from 'marked';
import { ChatSource } from '../../features/chat/chat-message';
import { matchCitationAtStart } from '../../features/chat/citations';

const ALLOWED_TAGS = [
  'p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'em', 'del', 'ul', 'ol', 'li',
  'blockquote', 'code', 'pre', 'a', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'button',
];
const ALLOWED_ATTRIBUTES = [
  'href', 'title', 'class', 'start', 'align', 'type', 'data-source', 'aria-label', 'data-cite',
];
const ALLOWED_URL = /^(?:https?:|mailto:|#)/i;
const CODE_LANGUAGE_CLASS = /^language-[\w-]+$/;

const CITATION_CLASS = 'citation';

const HTML_ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

/** An unfinished marker at the end of a streaming answer: `[`, `[1`, `[1,`, `[1-`, `[1, 2`. */
const UNFINISHED_CITATION = /\[[\d\s,-]*$/;

export interface RenderOptions {
  /** Sources of the answer; a marker `[n]` becomes a citation button only when `n` is one of them. */
  sources?: readonly ChatSource[];
  /** The answer is still arriving: an unfinished citation marker at the end is not shown yet. */
  streaming?: boolean;
}

function newNonce(): string {
  return crypto.randomUUID();
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (character) => HTML_ESCAPES[character]);
}

/**
 * Turns the (possibly incomplete) Markdown of the assistant into sanitized HTML.
 *
 * This is the only place of the application that trusts HTML (`bypassSecurityTrustHtml`): what it
 * returns has gone through an allow-list, so nothing else may bind `[innerHTML]` to other strings.
 * See ADR-0014 and `specs/LAB-14-chat-streaming-markdown-ui/contracts/markdown-rendering.md`.
 */
@Injectable({ providedIn: 'root' })
export class MarkdownRenderer {
  private readonly sanitizer = inject(DomSanitizer);

  /** Titles of the sources of the answer being rendered, by number; empty when it has none. */
  private titles = new Map<number, string>();
  /** Secret of the render in progress: only the buttons made here carry it, so the model cannot forge a citation. */
  private nonce = '';

  private readonly marked: Marked;

  private readonly purify = DOMPurify(window);

  constructor() {
    const tokenize = this.tokenizeCitation.bind(this);
    const renderCitation = this.renderCitation.bind(this);
    this.marked = new Marked({
      gfm: true,
      extensions: [
        {
          name: 'citation',
          level: 'inline',
          start: (source: string) => source.indexOf('['),
          tokenizer(this: TokenizerThis, source: string): Tokens.Generic | undefined {
            return tokenize(source, this.lexer.state.inLink);
          },
          renderer: (token: Tokens.Generic) => renderCitation(token),
        },
      ],
      renderer: {
        // Remote images are never loaded automatically: show the alternative text as a link.
        image: ({ href, text }) =>
          `<a href="${escapeHtml(href)}">${escapeHtml(text || href)}</a>`,
      },
    });
    this.purify.addHook('afterSanitizeAttributes', (node) => {
      if (node.tagName === 'A') {
        node.setAttribute('target', '_blank');
        node.setAttribute('rel', 'noopener noreferrer');
      }
      // `class` is only kept to know the language of a code block and to style a citation button.
      const className = node.getAttribute('class');
      const keepsClass =
        (node.tagName === 'CODE' && CODE_LANGUAGE_CLASS.test(className ?? '')) ||
        (node.tagName === 'BUTTON' && className === CITATION_CLASS);
      if (className !== null && !keepsClass) {
        node.removeAttribute('class');
      }
    });
  }

  render(markdown: string, options: RenderOptions = {}): SafeHtml {
    this.titles = new Map((options.sources ?? []).map((source) => [source.number, source.title]));
    this.nonce = newNonce();
    const text =
      options.streaming && this.titles.size > 0 ? markdown.replace(UNFINISHED_CITATION, '') : markdown;
    const html = this.marked.parse(text, { async: false });
    const fragment = this.purify.sanitize(html, {
      ALLOWED_TAGS,
      ALLOWED_ATTR: ALLOWED_ATTRIBUTES,
      ALLOWED_URI_REGEXP: ALLOWED_URL,
      // Plain values (`start="3"`, `align="center"`, `type="button"`) must not be checked against the URL pattern.
      ADD_URI_SAFE_ATTR: ['start', 'align', 'type'],
      RETURN_DOM_FRAGMENT: true,
    });
    return this.sanitizer.bypassSecurityTrustHtml(this.keepOwnButtons(fragment));
  }

  /** A marker is a citation only outside links and when the answer has sources; `[1](url)` stays a link. */
  private tokenizeCitation(source: string, inLink: boolean): Tokens.Generic | undefined {
    if (this.titles.size === 0 || inLink) {
      return undefined;
    }
    const match = matchCitationAtStart(source);
    if (match === null || source[match.end] === '(') {
      return undefined;
    }
    return { type: 'citation', raw: source.slice(0, match.end), numbers: match.numbers };
  }

  /** A button per number with a source; the others stay visible as plain text, not as a citation. */
  private renderCitation(token: Tokens.Generic): string {
    const numbers = token['numbers'] as number[];
    if (!numbers.some((number) => this.titles.has(number))) {
      return escapeHtml(token.raw);
    }
    return numbers
      .map((number) => {
        const title = this.titles.get(number);
        return title === undefined
          ? escapeHtml(`[${number}]`)
          : `<button type="button" class="${CITATION_CLASS}" data-source="${number}" data-cite="${this.nonce}" ` +
              `aria-label="${escapeHtml(`Fuente ${number}: ${title}`)}">[${number}]</button>`;
      })
      .join(' ');
  }

  /**
   * Buttons are allowed through the sanitizer only so that the citations survive it. A button that does not
   * carry this render's secret was written by the model and becomes plain text.
   */
  private keepOwnButtons(fragment: DocumentFragment): string {
    for (const button of Array.from(fragment.querySelectorAll('button'))) {
      if (button.getAttribute('data-cite') === this.nonce) {
        button.removeAttribute('data-cite');
      } else {
        button.replaceWith(document.createTextNode(button.textContent ?? ''));
      }
    }
    const container = document.createElement('div');
    container.appendChild(fragment);
    return container.innerHTML;
  }
}
