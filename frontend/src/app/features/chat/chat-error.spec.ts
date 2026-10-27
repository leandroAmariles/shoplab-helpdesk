import { ChatErrorKind, ChatStreamError, toUserMessage } from './chat-error';

describe('toUserMessage', () => {
  const kinds: ChatErrorKind[] = ['network', 'validation', 'unavailable', 'server', 'stream'];

  it.each(kinds)('returns a Spanish message for %s', (kind) => {
    expect(toUserMessage(kind).length).toBeGreaterThan(0);
  });

  it('gives each kind a different message', () => {
    expect(new Set(kinds.map(toUserMessage)).size).toBe(kinds.length);
  });

  it('explains the length limit for validation errors', () => {
    expect(toUserMessage('validation')).toContain('4000');
  });
});

describe('ChatStreamError', () => {
  it('keeps the debugging fields out of the user message', () => {
    const error = new ChatStreamError('stream', 503, 'SLHD_CHAT_00003', 'model failed');

    expect(toUserMessage(error.kind)).not.toContain('SLHD_CHAT_00003');
    expect(toUserMessage(error.kind)).not.toContain('model failed');
    expect(error.code).toBe('SLHD_CHAT_00003');
    expect(error.status).toBe(503);
  });
});
