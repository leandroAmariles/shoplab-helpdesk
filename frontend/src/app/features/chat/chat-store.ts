import { computed, DestroyRef, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { ChatErrorKind, ChatStreamError } from './chat-error';
import { ChatMessage, ChatStreamEvent, MAX_MESSAGE_LENGTH } from './chat-message';
import { ChatStreamClient } from './chat-stream.client';

/** Runs a callback on the next animation frame; returns a function that cancels it. */
export type FrameScheduler = (callback: () => void) => () => void;

export const FRAME_SCHEDULER = new InjectionToken<FrameScheduler>('FRAME_SCHEDULER', {
  providedIn: 'root',
  factory: () => (callback) => {
    const handle = requestAnimationFrame(callback);
    return () => cancelAnimationFrame(handle);
  },
});

/**
 * State of the conversation on screen. Provided by the chat component, not by the root, so the
 * conversation and its open connection die with the screen.
 */
@Injectable()
export class ChatStore {
  private readonly client = inject(ChatStreamClient);
  private readonly schedule = inject(FRAME_SCHEDULER);

  readonly messages = signal<ChatMessage[]>([]);
  readonly draft = signal('');

  readonly isStreaming = computed(() => this.messages().at(-1)?.status === 'streaming');

  readonly lastUserMessage = computed(
    () => this.messages().filter((message) => message.author === 'user').at(-1)?.content ?? '',
  );

  readonly canSend = computed(() => {
    const draft = this.draft();
    return draft.trim().length > 0 && draft.length <= MAX_MESSAGE_LENGTH && !this.isStreaming();
  });

  private subscription?: Subscription;
  private cancelFrame?: () => void;
  private pendingText = '';
  private currentAssistantId = '';
  private requestId = 0;
  private nextMessageId = 0;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.cancelFrame?.();
      this.subscription?.unsubscribe();
    });
  }

  /** Sends the draft. A no-op when it cannot be sent, which also absorbs a double Enter. */
  send(): void {
    if (!this.canSend()) {
      return;
    }
    const text = this.draft();
    this.draft.set('');
    this.messages.update((messages) => [...messages, this.newMessage('user', text, 'complete')]);
    this.start(text);
  }

  /** Resends the last user message after a failure, with a new assistant bubble. */
  retry(): void {
    const text = this.lastUserMessage();
    if (text === '' || this.isStreaming()) {
      return;
    }
    this.start(text);
  }

  /** Stops the answer in progress keeping the text received so far. */
  stop(): void {
    if (!this.isStreaming()) {
      return;
    }
    this.flush();
    this.requestId++;
    this.subscription?.unsubscribe();
    this.updateAssistant({ status: 'stopped' });
  }

  private start(text: string): void {
    const requestId = ++this.requestId;
    const assistant = this.newMessage('assistant', '', 'streaming');
    this.currentAssistantId = assistant.id;
    this.pendingText = '';
    this.messages.update((messages) => [...messages, assistant]);

    this.subscription = this.client.stream(text).subscribe({
      next: (event) => this.onEvent(requestId, event),
      error: (error: unknown) => this.onError(requestId, error),
    });
  }

  private onEvent(requestId: number, event: ChatStreamEvent): void {
    if (requestId !== this.requestId) {
      return;
    }
    if (event.type === 'token') {
      this.pendingText += event.content;
      this.cancelFrame ??= this.schedule(() => {
        this.cancelFrame = undefined;
        this.flush();
      });
    } else if (event.type === 'done') {
      this.flush();
      this.updateAssistant({ status: 'complete' });
    } else {
      this.fail('stream');
    }
  }

  private onError(requestId: number, error: unknown): void {
    if (requestId !== this.requestId) {
      return;
    }
    this.fail(error instanceof ChatStreamError ? error.kind : 'stream');
  }

  private fail(kind: ChatErrorKind): void {
    this.flush();
    this.updateAssistant({ status: 'failed', error: kind });
    // Only the kind is logged: never the conversation text.
    console.warn(`Chat: la respuesta falló (${kind}).`);
  }

  /** Moves the text received since the last frame into the assistant message. */
  private flush(): void {
    this.cancelFrame?.();
    this.cancelFrame = undefined;
    if (this.pendingText === '') {
      return;
    }
    const text = this.pendingText;
    this.pendingText = '';
    this.messages.update((messages) =>
      messages.map((message) =>
        message.id === this.currentAssistantId
          ? { ...message, content: message.content + text }
          : message,
      ),
    );
  }

  private updateAssistant(patch: Partial<ChatMessage>): void {
    this.messages.update((messages) =>
      messages.map((message) =>
        message.id === this.currentAssistantId ? { ...message, ...patch } : message,
      ),
    );
  }

  private newMessage(
    author: ChatMessage['author'],
    content: string,
    status: ChatMessage['status'],
  ): ChatMessage {
    return { id: `message-${++this.nextMessageId}`, author, content, status };
  }
}
