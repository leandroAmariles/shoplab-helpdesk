import { ChatSource } from './chat-message';
import { citedNumbers, findCitations, matchCitationAtStart, parseCitations } from './citations';

function source(number: number): ChatSource {
  return { number, title: `Documento ${number}`, section: '', text: `Texto ${number}` };
}

describe('citations', () => {
  describe('findCitations', () => {
    it.each([
      ['[1]', [[1]]],
      ['[1][2]', [[1], [2]]],
      ['[1, 2]', [[1, 2]]],
      ['[1,2]', [[1, 2]]],
      ['[1-3]', [[1, 2, 3]]],
      ['[1, 3-4]', [[1, 3, 4]]],
      ['[ 2 ]', [[2]]],
      ['Plazo de 30 días [1] y reembolso [2].', [[1], [2]]],
    ])('recognises %s', (text, expected) => {
      expect(findCitations(text).map((match) => match.numbers)).toEqual(expected);
    });

    it.each(['[3-1]', '[1-30]', '[0]', '[]', '[abc]', '[1a]', '[2024]', '(1)', '【1】', '[1,]', '[,1]', '[1 2]'])(
      'does not recognise %s',
      (text) => {
        expect(findCitations(text)).toEqual([]);
      },
    );

    it('reports where each marker starts and ends', () => {
      const [match] = findCitations('Hola [1, 2] fin');

      expect(match.start).toBe(5);
      expect(match.end).toBe(11);
    });
  });

  describe('matchCitationAtStart', () => {
    it('matches a marker at the very start only', () => {
      expect(matchCitationAtStart('[1, 2] resto')).toEqual({ start: 0, end: 6, numbers: [1, 2] });
      expect(matchCitationAtStart('antes [1]')).toBeNull();
      expect(matchCitationAtStart('[3-1] resto')).toBeNull();
    });
  });

  describe('parseCitations', () => {
    it('splits the numbers into valid and invalid for the sources of the answer', () => {
      expect(parseCitations('Ver [1] y [9].', 4)).toEqual({ valid: [1], invalid: [9] });
    });

    it('judges every number of a list on its own', () => {
      expect(parseCitations('[1, 9]', 4)).toEqual({ valid: [1], invalid: [9] });
    });

    it('treats every number as invalid when there are no sources', () => {
      expect(parseCitations('[1]', 0)).toEqual({ valid: [], invalid: [1] });
    });

    it('has nothing to report without markers', () => {
      expect(parseCitations('Sin citas, solo [opcional] y (1).', 4)).toEqual({ valid: [], invalid: [] });
    });
  });

  describe('citedNumbers', () => {
    const sources = [source(1), source(2), source(3), source(4)];

    it('lists the valid numbers once, in order of first appearance', () => {
      expect(citedNumbers('Dato [3]. Otro [1]. Otra vez [3] y [1, 2].', sources)).toEqual([3, 1, 2]);
    });

    it('leaves out the numbers without a source', () => {
      expect(citedNumbers('Dato [7] y [1].', sources)).toEqual([1]);
    });

    it('is empty when nothing is cited', () => {
      expect(citedNumbers('Sin citas.', sources)).toEqual([]);
    });

    it('is empty when there are no sources', () => {
      expect(citedNumbers('Dato [1].', [])).toEqual([]);
    });
  });
});
