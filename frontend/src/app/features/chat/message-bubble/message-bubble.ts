import { Component, computed, inject, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MarkdownRenderer } from '../../../core/markdown/markdown-renderer';
import { toUserMessage } from '../chat-error';
import { ChatMessage, ChatSource } from '../chat-message';
import { citedNumbers } from '../citations';
import { SourceList } from '../source-list/source-list';
import { SourcePreview } from '../source-preview/source-preview';

@Component({
  selector: 'app-message-bubble',
  imports: [MatButtonModule, MatIconModule, SourceList],
  templateUrl: './message-bubble.html',
  styleUrl: './message-bubble.scss',
})
export class MessageBubble {
  private readonly renderer = inject(MarkdownRenderer);
  private readonly dialog = inject(MatDialog);

  readonly message = input.required<ChatMessage>();
  /** Only the last failed answer offers to retry. */
  readonly canRetry = input(false);
  readonly retry = output<void>();

  protected readonly isUser = computed(() => this.message().author === 'user');
  protected readonly authorLabel = computed(() => (this.isUser() ? 'Tú' : 'Asistente'));
  protected readonly errorText = computed(() => {
    const error = this.message().error;
    return error ? toUserMessage(error) : '';
  });
  /** Sanitized HTML of the assistant's Markdown; only read for assistant messages. */
  protected readonly html = computed(() => {
    const { content, sources, status } = this.message();
    return this.renderer.render(content, { sources, streaming: status === 'streaming' });
  });
  /**
   * What goes under the answer: the sources the text cites (valid numbers, once each, in order of first appearance)
   * or, when a finished answer cites none, every source consulted under its own label so the customer can still
   * check where it came from without being told the bot attributed anything to them.
   */
  protected readonly listing = computed(() => {
    const { content, sources, status } = this.message();
    const cited = citedNumbers(content, sources).map(
      (number) => sources.find((source) => source.number === number)!,
    );
    if (cited.length > 0) {
      return { label: 'Fuentes', sources: cited };
    }
    const finished = status === 'complete' || status === 'stopped';
    return { label: 'Fuentes consultadas', sources: finished ? sources : [] };
  });

  /** The `[innerHTML]` of the answer cannot carry Angular bindings, so the clicks on its citations are caught here. */
  protected onMarkdownClick(event: Event): void {
    const citation = (event.target as HTMLElement | null)?.closest<HTMLElement>('button[data-source]');
    const source = this.message().sources.find((s) => String(s.number) === citation?.dataset['source']);
    if (source !== undefined) {
      this.openSource(source);
    }
  }

  /** One fragment at a time: opening another replaces the one on screen. */
  protected openSource(source: ChatSource): void {
    this.dialog.closeAll();
    this.dialog.open(SourcePreview, {
      data: source,
      width: '640px',
      maxWidth: '95vw',
      autoFocus: 'dialog',
    });
  }
}
