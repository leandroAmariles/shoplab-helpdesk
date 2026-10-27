import { inject, Injectable } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import DOMPurify from 'dompurify';
import { Marked } from 'marked';

const ALLOWED_TAGS = [
  'p', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'em', 'del', 'ul', 'ol', 'li',
  'blockquote', 'code', 'pre', 'a', 'table', 'thead', 'tbody', 'tr', 'th', 'td',
];
const ALLOWED_ATTRIBUTES = ['href', 'title', 'class', 'start', 'align'];
const ALLOWED_URL = /^(?:https?:|mailto:|#)/i;
const CODE_LANGUAGE_CLASS = /^language-[\w-]+$/;

const HTML_ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

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

  private readonly marked = new Marked({
    gfm: true,
    renderer: {
      // Remote images are never loaded automatically: show the alternative text as a link.
      image: ({ href, text }) =>
        `<a href="${escapeHtml(href)}">${escapeHtml(text || href)}</a>`,
    },
  });

  private readonly purify = DOMPurify(window);

  constructor() {
    this.purify.addHook('afterSanitizeAttributes', (node) => {
      if (node.tagName === 'A') {
        node.setAttribute('target', '_blank');
        node.setAttribute('rel', 'noopener noreferrer');
      }
      // `class` is only kept to know the language of a code block.
      const className = node.getAttribute('class');
      if (className !== null && !(node.tagName === 'CODE' && CODE_LANGUAGE_CLASS.test(className))) {
        node.removeAttribute('class');
      }
    });
  }

  render(markdown: string): SafeHtml {
    const html = this.marked.parse(markdown, { async: false });
    const clean = this.purify.sanitize(html, {
      ALLOWED_TAGS,
      ALLOWED_ATTR: ALLOWED_ATTRIBUTES,
      ALLOWED_URI_REGEXP: ALLOWED_URL,
      // Plain values (`start="3"`, `align="center"`) must not be checked against the URL pattern.
      ADD_URI_SAFE_ATTR: ['start', 'align'],
    });
    return this.sanitizer.bypassSecurityTrustHtml(clean);
  }
}
