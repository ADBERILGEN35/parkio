import { elapsedMsFromStartedAt, formatElapsedFromStartedAt } from '../elapsedDuration';

describe('elapsedDuration', () => {
  const startedAt = '2026-07-21T09:00:00.000Z';

  it('derives elapsed from startedAt and current time', () => {
    const now = Date.parse(startedAt) + 125_000;
    expect(elapsedMsFromStartedAt(startedAt, now)).toBe(125_000);
    expect(formatElapsedFromStartedAt(startedAt, now)).toBe('02:05');
  });

  it('renders zero at the start instant', () => {
    expect(formatElapsedFromStartedAt(startedAt, Date.parse(startedAt))).toBe('00:00');
  });

  it('formats hours for long durations', () => {
    const now = Date.parse(startedAt) + 3_661_000;
    expect(formatElapsedFromStartedAt(startedAt, now)).toBe('1:01:01');
  });

  it('fails safe on invalid startedAt', () => {
    expect(elapsedMsFromStartedAt('not-a-date', Date.now())).toBeNull();
    expect(formatElapsedFromStartedAt('not-a-date', Date.now())).toBe('00:00');
    expect(formatElapsedFromStartedAt('not-a-date', Date.now())).not.toMatch(/NaN|Infinity|-/);
  });

  it('clamps future startedAt to zero', () => {
    const future = '2099-01-01T00:00:00.000Z';
    expect(elapsedMsFromStartedAt(future, Date.parse('2026-07-21T09:00:00.000Z'))).toBe(0);
    expect(formatElapsedFromStartedAt(future, Date.parse('2026-07-21T09:00:00.000Z'))).toBe('00:00');
  });

  it('never returns raw timestamps', () => {
    const rendered = formatElapsedFromStartedAt(startedAt, Date.parse(startedAt) + 5_000);
    expect(rendered).not.toContain(startedAt);
    expect(rendered).not.toContain('T');
  });
});