import { ChatErrorKind } from './chat-error';

/** Maximum message length accepted by the backend (LAB-12). */
export const MAX_MESSAGE_LENGTH = 4000;

/** The character counter is shown from this length on. */
export const COUNTER_THRESHOLD = 3500;

export type MessageAuthor = 'user' | 'assistant';

export type MessageStatus = 'streaming' | 'complete' | 'stopped' | 'failed';

export interface ChatMessage {
  id: string;
  author: MessageAuthor;
  /** Literal text for the user; Markdown for the assistant. */
  content: string;
  status: MessageStatus;
  /** Present only when `status` is `failed`. */
  error?: ChatErrorKind;
}

/** Events of the `POST /api/chat` stream (contract of LAB-12). */
export type ChatStreamEvent =
  | { type: 'token'; content: string }
  | { type: 'done'; fragments: number }
  | { type: 'error'; code: string; message: string };
