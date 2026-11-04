import { ChatErrorKind } from './chat-error';

/** Maximum message length accepted by the backend (LAB-12). */
export const MAX_MESSAGE_LENGTH = 4000;

/** The character counter is shown from this length on. */
export const COUNTER_THRESHOLD = 3500;

export type MessageAuthor = 'user' | 'assistant';

export type MessageStatus = 'streaming' | 'complete' | 'stopped' | 'failed';

/** A fragment of the knowledge base that backs an answer; `number` is what `[n]` cites (LAB-23). */
export interface ChatSource {
  /** Starts at 1 and is unique within one answer. */
  number: number;
  /** Readable title of the document, never its file name. */
  title: string;
  /** Path of headings that contains the fragment; empty when there is none. */
  section: string;
  /** Text of the fragment as it was handed to the model (Markdown from the corpus). */
  text: string;
}

export interface ChatMessage {
  id: string;
  author: MessageAuthor;
  /** Literal text for the user; Markdown for the assistant. */
  content: string;
  status: MessageStatus;
  /** Present only when `status` is `failed`. */
  error?: ChatErrorKind;
  /** Fragments the answer was generated from; empty for the user and for answers without context. */
  sources: ChatSource[];
}

/** Events of the `POST /api/chat` stream (contract of LAB-12). */
export type ChatStreamEvent =
  | { type: 'sources'; sources: ChatSource[] }
  | { type: 'token'; content: string }
  | { type: 'done'; fragments: number }
  | { type: 'error'; code: string; message: string };
