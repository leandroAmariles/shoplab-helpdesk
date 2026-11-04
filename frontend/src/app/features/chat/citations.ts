import { ChatSource } from './chat-message';

/** Most numbers a single marker may expand to (`[1-20]`); a wider range is not a citation. */
const MAX_NUMBERS_PER_MARKER = 20;

/**
 * Grammar of a citation marker, shared with the backend (`specs/LAB-23-source-citations/contracts/sources-event.md` §3):
 * `[` one or more numbers of 1 or 2 digits or ranges `a-b`, separated by commas, `]`.
 */
const CITATION_SOURCE =
  String.raw`\[\s*(\d{1,2}(?:\s*-\s*\d{1,2})?(?:\s*,\s*\d{1,2}(?:\s*-\s*\d{1,2})?)*)\s*\]`;

export interface CitationMatch {
  /** Index of the `[` in the text. */
  start: number;
  /** Index just after the `]`. */
  end: number;
  /** The numbers of the marker, with lists and ranges expanded. */
  numbers: number[];
}

export interface ParsedCitations {
  valid: number[];
  invalid: number[];
}

/** Expands `1, 3-4` into `[1, 3, 4]`; `null` when a number is zero or a range is backwards or too wide. */
function expand(inner: string): number[] | null {
  const numbers: number[] = [];
  for (const part of inner.split(',')) {
    const [from, to = from] = part.split('-').map((value) => Number(value.trim()));
    if (from < 1 || to < from || to - from + 1 > MAX_NUMBERS_PER_MARKER) {
      return null;
    }
    for (let number = from; number <= to; number++) {
      numbers.push(number);
    }
  }
  return numbers.length <= MAX_NUMBERS_PER_MARKER ? numbers : null;
}

/** Every well-formed marker of the text, in order. Anything else between brackets is ordinary text. */
export function findCitations(text: string): CitationMatch[] {
  const matches: CitationMatch[] = [];
  for (const found of text.matchAll(new RegExp(CITATION_SOURCE, 'g'))) {
    const numbers = expand(found[1]);
    if (numbers !== null) {
      matches.push({ start: found.index, end: found.index + found[0].length, numbers });
    }
  }
  return matches;
}

/** The marker that starts exactly at the beginning of `text`, if any (what a Markdown inline tokenizer needs). */
export function matchCitationAtStart(text: string): CitationMatch | null {
  const found = new RegExp(`^${CITATION_SOURCE}`).exec(text);
  const numbers = found === null ? null : expand(found[1]);
  return found === null || numbers === null ? null : { start: 0, end: found[0].length, numbers };
}

/** Splits the cited numbers into those that point at one of `sourceCount` sources and those that do not. */
export function parseCitations(text: string, sourceCount: number): ParsedCitations {
  const parsed: ParsedCitations = { valid: [], invalid: [] };
  for (const match of findCitations(text)) {
    for (const number of match.numbers) {
      (number <= sourceCount ? parsed.valid : parsed.invalid).push(number);
    }
  }
  return parsed;
}

/** Numbers of the sources the answer cites: valid only, no repeats, in order of first appearance. */
export function citedNumbers(content: string, sources: readonly ChatSource[]): number[] {
  const known = new Set(sources.map((source) => source.number));
  const cited: number[] = [];
  for (const match of findCitations(content)) {
    for (const number of match.numbers) {
      if (known.has(number) && !cited.includes(number)) {
        cited.push(number);
      }
    }
  }
  return cited;
}
