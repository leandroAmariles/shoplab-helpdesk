import { newConversationId } from './conversation-id';

const CANONICAL_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe('newConversationId', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('is a canonical version 4 UUID', () => {
    expect(newConversationId()).toMatch(CANONICAL_V4);
  });

  it('is different every time', () => {
    const ids = new Set(Array.from({ length: 50 }, () => newConversationId()));

    expect(ids.size).toBe(50);
  });

  it('still builds a canonical UUID where randomUUID is not available (plain HTTP)', () => {
    const real = globalThis.crypto;
    vi.stubGlobal('crypto', {
      getRandomValues: (array: Uint8Array<ArrayBuffer>) => real.getRandomValues(array),
    });

    expect(newConversationId()).toMatch(CANONICAL_V4);
  });
});
