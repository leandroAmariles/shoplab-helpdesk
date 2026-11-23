import { formatDate, formatDateTime, formatShortDate } from './eval-format';

describe('eval-format', () => {
  // A local time, so the expectations do not depend on the time zone of the machine.
  const iso = new Date(2026, 10, 3, 9, 5).toISOString();

  it('writes the date as day/month/year', () => {
    expect(formatDate(iso)).toBe('03/11/2026');
  });

  it('writes the short date as day/month', () => {
    expect(formatShortDate(iso)).toBe('03/11');
  });

  it('writes the date and the time with two digits each', () => {
    expect(formatDateTime(iso)).toBe('03/11/2026 09:05');
  });
});
