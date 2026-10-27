import { Component, computed, inject, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MarkdownRenderer } from '../../../core/markdown/markdown-renderer';
import { toUserMessage } from '../chat-error';
import { ChatMessage } from '../chat-message';

@Component({
  selector: 'app-message-bubble',
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './message-bubble.html',
  styleUrl: './message-bubble.scss',
})
export class MessageBubble {
  private readonly renderer = inject(MarkdownRenderer);

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
  protected readonly html = computed(() => this.renderer.render(this.message().content));
}
