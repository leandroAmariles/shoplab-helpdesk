export interface SseEvent {
  /** Event name; `message` when the server does not send one. */
  event: string;
  data: string;
}

export interface SseParser {
  /** Feeds decoded text cut anywhere and returns the events completed by it. */
  push(chunk: string): SseEvent[];
}

const DEFAULT_EVENT = 'message';

/** Incremental parser of the `text/event-stream` format. Pure: no Angular, no I/O. */
export function createSseParser(): SseParser {
  let buffer = '';
  let eventName = '';
  let dataLines: string[] = [];

  function readLine(): string | null {
    const end = buffer.search(/[\r\n]/);
    if (end === -1) {
      return null;
    }
    // A trailing CR may be the first half of a CRLF pair: wait for the next chunk.
    if (buffer[end] === '\r' && end === buffer.length - 1) {
      return null;
    }
    const line = buffer.slice(0, end);
    const terminatorLength = buffer[end] === '\r' && buffer[end + 1] === '\n' ? 2 : 1;
    buffer = buffer.slice(end + terminatorLength);
    return line;
  }

  function applyField(line: string): void {
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) {
      value = value.slice(1);
    }
    if (field === 'event') {
      eventName = value;
    } else if (field === 'data') {
      dataLines.push(value);
    }
  }

  return {
    push(chunk: string): SseEvent[] {
      buffer += chunk;
      const events: SseEvent[] = [];
      for (let line = readLine(); line !== null; line = readLine()) {
        if (line === '') {
          if (dataLines.length > 0) {
            events.push({ event: eventName || DEFAULT_EVENT, data: dataLines.join('\n') });
          }
          eventName = '';
          dataLines = [];
        } else if (!line.startsWith(':')) {
          applyField(line);
        }
      }
      return events;
    },
  };
}
