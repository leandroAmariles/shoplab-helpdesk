/**
 * A new, unpredictable identifier for a conversation: a version 4 UUID in its canonical text form, which is what the
 * backend accepts. `crypto.randomUUID` only exists in secure contexts (HTTPS or localhost), so on plain HTTP the
 * same format is built from `crypto.getRandomValues`, which is always available.
 */
export function newConversationId(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
