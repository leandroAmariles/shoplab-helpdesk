import { createSseParser, SseEvent } from './sse-parser';

function pushAll(chunks: string[]): SseEvent[] {
  const parser = createSseParser();
  return chunks.flatMap((chunk) => parser.push(chunk));
}

describe('createSseParser', () => {
  const stream = 'event: token\ndata: {"content":"Hola"}\n\nevent: done\ndata: {"fragments":1}\n\n';
  const expected: SseEvent[] = [
    { event: 'token', data: '{"content":"Hola"}' },
    { event: 'done', data: '{"fragments":1}' },
  ];

  it('emits complete events', () => {
    expect(pushAll([stream])).toEqual(expected);
  });

  it('treats CRLF like LF', () => {
    expect(pushAll([stream.replaceAll('\n', '\r\n')])).toEqual(expected);
  });

  it('emits an event split across calls only once, when complete', () => {
    const parser = createSseParser();

    expect(parser.push('event: token\nda')).toEqual([]);
    expect(parser.push('ta: {"content":"x"}\n')).toEqual([]);
    expect(parser.push('\n')).toEqual([{ event: 'token', data: '{"content":"x"}' }]);
  });

  it('handles one character per call', () => {
    expect(pushAll([...stream])).toEqual(expected);
  });

  it('handles a CRLF pair split between calls', () => {
    expect(pushAll(['event: token\r', '\ndata: a\r', '\n\r', '\n'])).toEqual([
      { event: 'token', data: 'a' },
    ]);
  });

  it('joins several data lines with a newline', () => {
    expect(pushAll(['data: first\ndata: second\n\n'])).toEqual([
      { event: 'message', data: 'first\nsecond' },
    ]);
  });

  it('ignores comment lines', () => {
    expect(pushAll([': keep-alive\nevent: token\ndata: x\n\n'])).toEqual([
      { event: 'token', data: 'x' },
    ]);
  });

  it('does not emit on a blank line without data', () => {
    expect(pushAll(['\n\n\nevent: token\n\n'])).toEqual([]);
  });

  it('removes only the first space after the colon', () => {
    expect(pushAll(['data:  two spaces\n\n'])).toEqual([{ event: 'message', data: ' two spaces' }]);
    expect(pushAll(['data:no-space\n\n'])).toEqual([{ event: 'message', data: 'no-space' }]);
  });

  it('defaults the event name to message', () => {
    expect(pushAll(['data: x\n\n'])).toEqual([{ event: 'message', data: 'x' }]);
  });

  it('resets the event name between events', () => {
    expect(pushAll(['event: token\ndata: a\n\ndata: b\n\n'])).toEqual([
      { event: 'token', data: 'a' },
      { event: 'message', data: 'b' },
    ]);
  });

  it('reproduces the same events for 200 tokens cut at random positions', () => {
    const events = Array.from({ length: 200 }, (_, i) => `event: token\ndata: {"content":"t${i}"}\n\n`);
    const text = events.join('');
    let seed = 42;
    const random = () => {
      seed = (seed * 1664525 + 1013904223) % 4294967296;
      return seed / 4294967296;
    };
    const chunks: string[] = [];
    for (let index = 0; index < text.length; ) {
      const size = 1 + Math.floor(random() * 17);
      chunks.push(text.slice(index, index + size));
      index += size;
    }

    const parsed = pushAll(chunks);

    expect(parsed).toHaveLength(200);
    expect(parsed.map((e) => e.data)).toEqual(
      Array.from({ length: 200 }, (_, i) => `{"content":"t${i}"}`),
    );
  });
});
