import { computed, DestroyRef, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { ChatErrorKind, ChatStreamError } from './chat-error';
import { ChatFeedbackClient } from './chat-feedback.client';
import {
  ChatMessage,
  ChatStreamEvent,
  FeedbackRating,
  MAX_FEEDBACK_COMMENT_LENGTH,
  MAX_MESSAGE_LENGTH,
  MessageFeedbackState,
} from './chat-message';
import { ChatStreamClient } from './chat-stream.client';
import { newConversationId } from './conversation-id';

/** Runs a callback on the next animation frame; returns a function that cancels it. */
export type FrameScheduler = (callback: () => void) => () => void;

export const FRAME_SCHEDULER = new InjectionToken<FrameScheduler>('FRAME_SCHEDULER', {
  providedIn: 'root',
  factory: () => (callback) => {
    const handle = requestAnimationFrame(callback);
    return () => cancelAnimationFrame(handle);
  },
});

const FEEDBACK_ERROR_MESSAGE = 'No se pudo guardar tu valoración. Inténtalo de nuevo.';

function initialFeedbackState(): MessageFeedbackState {
  return {
    commentDraft: '',
    commentOpen: false,
    saving: false,
    saved: false,
  };
}

/**
 * State of the conversation on screen. Provided by the chat component, not by the root, so the
 * conversation and its open connection die with the screen.
 */
@Injectable()
export class ChatStore {
  private readonly client = inject(ChatStreamClient);
  private readonly feedbackClient = inject(ChatFeedbackClient);
  private readonly schedule = inject(FRAME_SCHEDULER);

  readonly messages = signal<ChatMessage[]>([]);
  readonly draft = signal('');

  readonly isStreaming = computed(() => this.messages().at(-1)?.status === 'streaming');

  readonly lastUserMessage = computed(
    () => this.messages().filter((message) => message.author === 'user').at(-1)?.content ?? '',
  );

  /** A conversation can be started anew once there is something to leave behind. */
  readonly canStartNewConversation = computed(() => this.messages().length > 0);

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
  /** Identifies the conversation for the backend, which remembers it; it never leaves the app but in requests. */
  private conversationId = newConversationId();

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

  /**
   * Starts a new conversation: the answer in progress is cancelled (and whatever it still sends is ignored), the
   * messages are cleared and the bot will not remember what was said. What the customer is typing is kept.
   */
  newConversation(): void {
    this.requestId++;
    this.cancelFrame?.();
    this.cancelFrame = undefined;
    this.pendingText = '';
    this.subscription?.unsubscribe();
    this.messages.set([]);
    this.conversationId = newConversationId();
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

  /** Sends a 👍 (`UP`) or 👎 (`DOWN`) rating for a completed assistant message in a single click. */
  rateMessage(messageId: string, rating: FeedbackRating): void {
    const message = this.messages().find((item) => item.id === messageId);
    if (
      !message ||
      message.author !== 'assistant' ||
      message.status !== 'complete' ||
      !message.turnId ||
      message.feedback?.saving
    ) {
      return;
    }
    const current = message.feedback ?? initialFeedbackState();
    const commentToSend = current.commentDraft.trim() || current.comment || null;
    this.updateMessageFeedback(messageId, {
      ...current,
      rating,
      saving: true,
      saved: false,
      error: undefined,
    });
    this.feedbackClient.submitFeedback(message.turnId, rating, commentToSend).subscribe({
      next: (response) => {
        const latest = this.findFeedback(messageId);
        this.updateMessageFeedback(messageId, {
          ...latest,
          rating: response.rating,
          comment: response.comment ?? undefined,
          commentDraft: response.comment ?? latest.commentDraft,
          saving: false,
          saved: true,
          error: undefined,
        });
      },
      error: () => {
        const latest = this.findFeedback(messageId);
        this.updateMessageFeedback(messageId, {
          ...latest,
          saving: false,
          saved: false,
          error: FEEDBACK_ERROR_MESSAGE,
        });
      },
    });
  }

  /** Opens or closes the optional comment input for a completed assistant message. */
  toggleFeedbackComment(messageId: string): void {
    const message = this.messages().find((item) => item.id === messageId);
    if (!message || message.author !== 'assistant' || message.status !== 'complete' || !message.turnId) {
      return;
    }
    const current = message.feedback ?? initialFeedbackState();
    this.updateMessageFeedback(messageId, {
      ...current,
      commentOpen: !current.commentOpen,
      commentDraft: current.commentDraft || current.comment || '',
    });
  }

  /** Updates the draft comment for a completed assistant message (capped at 500 characters). */
  updateFeedbackDraft(messageId: string, draft: string): void {
    const message = this.messages().find((item) => item.id === messageId);
    if (!message || message.author !== 'assistant' || message.status !== 'complete' || !message.turnId) {
      return;
    }
    const current = message.feedback ?? initialFeedbackState();
    this.updateMessageFeedback(messageId, {
      ...current,
      commentDraft: draft.slice(0, MAX_FEEDBACK_COMMENT_LENGTH),
    });
  }

  /** Submits the optional comment along with the current rating for the turn. */
  saveFeedbackComment(messageId: string, comment?: string): void {
    const message = this.messages().find((item) => item.id === messageId);
    const current = message?.feedback;
    if (
      !message ||
      message.author !== 'assistant' ||
      message.status !== 'complete' ||
      !message.turnId ||
      !current?.rating ||
      current.saving
    ) {
      return;
    }
    const draft = (comment ?? current.commentDraft).slice(0, MAX_FEEDBACK_COMMENT_LENGTH);
    this.updateMessageFeedback(messageId, {
      ...current,
      commentDraft: draft,
      saving: true,
      saved: false,
      error: undefined,
    });
    this.feedbackClient.submitFeedback(message.turnId, current.rating, draft).subscribe({
      next: (response) => {
        const latest = this.findFeedback(messageId);
        this.updateMessageFeedback(messageId, {
          ...latest,
          rating: response.rating,
          comment: response.comment ?? undefined,
          commentDraft: response.comment ?? '',
          commentOpen: false,
          saving: false,
          saved: true,
          error: undefined,
        });
      },
      error: () => {
        const latest = this.findFeedback(messageId);
        this.updateMessageFeedback(messageId, {
          ...latest,
          saving: false,
          saved: false,
          error: FEEDBACK_ERROR_MESSAGE,
        });
      },
    });
  }

  private start(text: string): void {
    const requestId = ++this.requestId;
    const assistant = this.newMessage('assistant', '', 'streaming');
    this.currentAssistantId = assistant.id;
    this.pendingText = '';
    this.messages.update((messages) => [...messages, assistant]);

    this.subscription = this.client.stream(text, this.conversationId).subscribe({
      next: (event) => this.onEvent(requestId, event),
      error: (error: unknown) => this.onError(requestId, error),
    });
  }

  private onEvent(requestId: number, event: ChatStreamEvent): void {
    if (requestId !== this.requestId) {
      return;
    }
    if (event.type === 'sources') {
      this.updateAssistant({ sources: event.sources });
    } else if (event.type === 'token') {
      this.pendingText += event.content;
      this.cancelFrame ??= this.schedule(() => {
        this.cancelFrame = undefined;
        this.flush();
      });
    } else if (event.type === 'done') {
      this.flush();
      this.updateAssistant({
        status: 'complete',
        ...(event.turnId !== undefined ? { turnId: event.turnId } : {}),
      });
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

  private findFeedback(messageId: string): MessageFeedbackState {
    return (
      this.messages().find((item) => item.id === messageId)?.feedback ?? initialFeedbackState()
    );
  }

  private updateMessageFeedback(messageId: string, feedback: MessageFeedbackState): void {
    this.messages.update((messages) =>
      messages.map((message) => (message.id === messageId ? { ...message, feedback } : message)),
    );
  }

  private newMessage(
    author: ChatMessage['author'],
    content: string,
    status: ChatMessage['status'],
  ): ChatMessage {
    return { id: `message-${++this.nextMessageId}`, author, content, status, sources: [] };
  }
}
