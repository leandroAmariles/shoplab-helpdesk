import {
  afterRenderEffect,
  Component,
  computed,
  ElementRef,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ChatStore } from './chat-store';
import { MessageBubble } from './message-bubble/message-bubble';
import { MessageInput } from './message-input/message-input';

/** Distance to the bottom (px) under which the history still follows the answer. */
const FOLLOW_THRESHOLD_PX = 24;

@Component({
  selector: 'app-chat',
  imports: [MessageBubble, MessageInput, MatButtonModule, MatIconModule],
  providers: [ChatStore],
  templateUrl: './chat.html',
  styleUrl: './chat.scss',
})
export class Chat {
  protected readonly store = inject(ChatStore);

  private readonly history = viewChild.required<ElementRef<HTMLElement>>('history');
  private readonly input = viewChild.required(MessageInput);

  /** True while the history follows the end; false once the user scrolls up to read. */
  protected readonly following = signal(true);

  /** Only the last answer can be retried, and only if it failed. */
  protected readonly retryableId = computed(() => {
    const last = this.store.messages().at(-1);
    return last?.status === 'failed' ? last.id : null;
  });

  constructor() {
    afterRenderEffect(() => {
      this.store.messages();
      if (untracked(this.following)) {
        this.scrollToEnd();
      }
    });
  }

  protected onSend(): void {
    this.store.send();
    this.following.set(true);
    this.input().focus();
  }

  protected onScroll(): void {
    const element = this.history().nativeElement;
    const distance = element.scrollHeight - element.scrollTop - element.clientHeight;
    this.following.set(distance <= FOLLOW_THRESHOLD_PX);
  }

  protected goToEnd(): void {
    this.following.set(true);
    this.scrollToEnd();
  }

  private scrollToEnd(): void {
    const element = this.history().nativeElement;
    element.scrollTop = element.scrollHeight;
  }
}
