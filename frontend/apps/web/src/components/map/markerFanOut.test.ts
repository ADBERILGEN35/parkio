import { describe, expect, it } from 'vitest';
import { FAN_OUT_GAP, FAN_OUT_MARKER_SIZE, fanOutMarkers, type FanOutBounds, type ScreenPoint } from './markerFanOut';

const PITCH = FAN_OUT_MARKER_SIZE + FAN_OUT_GAP;
const point = (id: string, x: number, y: number): ScreenPoint => ({ id, x, y });

/** Where each marker is drawn: its true position plus its offset. */
function drawn(points: readonly ScreenPoint[], bounds?: FanOutBounds) {
  const offsets = fanOutMarkers(points, bounds);
  return points.map((p) => {
    const [dx, dy] = offsets.get(p.id) ?? [Number.NaN, Number.NaN];
    return { id: p.id, x: p.x + dx, y: p.y + dy, dx, dy };
  });
}

/** Pairs of drawn markers whose 40×40 px boxes overlap. */
function overlapping(positions: { id: string; x: number; y: number }[]) {
  const pairs: string[] = [];
  positions.forEach((a, i) =>
    positions.slice(i + 1).forEach((b) => {
      if (Math.abs(a.x - b.x) < FAN_OUT_MARKER_SIZE && Math.abs(a.y - b.y) < FAN_OUT_MARKER_SIZE) pairs.push(`${a.id}/${b.id}`);
    }),
  );
  return pairs;
}

/** The smaller of the two axis distances between the closest pair: at least a marker plus the gap. */
function closestSpacing(positions: { x: number; y: number }[]) {
  let closest = Number.POSITIVE_INFINITY;
  positions.forEach((a, i) =>
    positions.slice(i + 1).forEach((b) => {
      closest = Math.min(closest, Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y)));
    }),
  );
  return closest;
}

describe('fanOutMarkers', () => {
  it('leaves markers that touch no other marker where they are', () => {
    const offsets = fanOutMarkers([point('a', 100, 100), point('b', 100 + PITCH, 100), point('c', 400, 300)]);
    expect([...offsets.values()]).toEqual([[0, 0], [0, 0], [0, 0]]);
  });

  it('spreads two markers at one point side by side, one pitch apart', () => {
    const offsets = fanOutMarkers([point('a', 200, 200), point('b', 200, 200)]);
    expect(offsets.get('a')).toEqual([-PITCH / 2, 0]);
    expect(offsets.get('b')).toEqual([PITCH / 2, 0]);
  });

  it.each([3, 4, 5, 6])('puts %i markers at one point on a ring around it, none covering another', (count) => {
    const points = Array.from({ length: count }, (_, index) => point(`m${index}`, 300, 300));
    const positions = drawn(points);
    expect(overlapping(positions)).toEqual([]);
    expect(closestSpacing(positions)).toBeGreaterThanOrEqual(PITCH - 1);
    const meanX = positions.reduce((sum, p) => sum + p.x, 0) / count;
    const meanY = positions.reduce((sum, p) => sum + p.y, 0) / count;
    expect(Math.abs(meanX - 300)).toBeLessThanOrEqual(1);
    expect(Math.abs(meanY - 300)).toBeLessThanOrEqual(1);
  });

  it('gives the same layout for the same input, in input order around the ring', () => {
    const points = [point('first', 250, 250), point('second', 250, 250), point('third', 250, 250), point('fourth', 250, 250)];
    expect(fanOutMarkers(points)).toEqual(fanOutMarkers(points));
    const positions = drawn(points);
    // Four markers start at the top-left corner and run clockwise.
    expect(positions[0].x).toBeLessThan(250);
    expect(positions[0].y).toBeLessThan(250);
    expect(positions[1].x).toBeGreaterThan(250);
    expect(positions[1].y).toBeLessThan(250);
    expect(positions[2].x).toBeGreaterThan(250);
    expect(positions[2].y).toBeGreaterThan(250);
  });

  it('fans out a chain of markers that each touch the next', () => {
    // a and c are a pitch apart, but both touch b.
    const positions = drawn([point('a', 100, 100), point('b', 130, 100), point('c', 160, 100)]);
    expect(positions.every((p) => p.dx !== 0 || p.dy !== 0)).toBe(true);
    expect(overlapping(positions)).toEqual([]);
  });

  it('merges a fanned group with a marker its ring would cover', () => {
    // c is clear of the pair's point, but the pair's ring would reach it.
    const positions = drawn([point('a', 100, 100), point('b', 100, 100), point('c', 150, 100)]);
    expect(overlapping(positions)).toEqual([]);
    expect(positions.find((p) => p.id === 'c')).not.toMatchObject({ dx: 0, dy: 0 });
  });

  it('lays out the dense /explore answer at its framed zoom with no marker covering another', () => {
    // The a11y suite's dense answer as framed at 1280x720: four car parks 80–230 m apart and a
    // coincident pair.
    const points = [
      point('1', 824, 154),
      point('2', 809, 149),
      point('3', 804, 140),
      point('4', 833, 142),
      point('5', 423, 560),
      point('6', 423, 560),
    ];
    const positions = drawn(points, { width: 1280, height: 656, top: 152 });
    expect(overlapping(positions)).toEqual([]);
    expect(positions.filter((p) => p.dx === 0 && p.dy === 0)).toEqual([]);
  });

  it('keeps a ring below the controls over the top of the map, and inside its edges', () => {
    const bounds: FanOutBounds = { width: 360, height: 736, top: 152 };
    const half = FAN_OUT_MARKER_SIZE / 2 + FAN_OUT_GAP;
    const top = drawn([point('a', 100, 160), point('b', 100, 160)], bounds);
    expect(top.every((p) => p.y >= bounds.top + half)).toBe(true);
    const left = drawn([point('a', 10, 400), point('b', 10, 400)], bounds);
    expect(left.every((p) => p.x >= half)).toBe(true);
    const right = drawn([point('a', 355, 400), point('b', 355, 400)], bounds);
    expect(right.every((p) => p.x <= bounds.width - half)).toBe(true);
    expect(overlapping([...top, ...left, ...right].map((p, i) => ({ ...p, id: `${p.id}${i}` })))).toEqual([]);
  });

  it('does not move a ring whose centre is off the visible map', () => {
    const offsets = fanOutMarkers([point('a', -100, 300), point('b', -100, 300)], { width: 360, height: 736, top: 152 });
    expect(offsets.get('a')).toEqual([-PITCH / 2, 0]);
    expect(offsets.get('b')).toEqual([PITCH / 2, 0]);
  });

  it('returns whole-pixel offsets and leaves the points it is given unchanged', () => {
    const points = Object.freeze([
      Object.freeze(point('a', 10.4, 20.6)),
      Object.freeze(point('b', 11.2, 21.9)),
      Object.freeze(point('c', 12.7, 19.1)),
    ]);
    const offsets = fanOutMarkers(points);
    expect([...offsets.values()].flat().every(Number.isInteger)).toBe(true);
    expect(points.map((p) => [p.x, p.y])).toEqual([[10.4, 20.6], [11.2, 21.9], [12.7, 19.1]]);
  });

  it('returns an offset for every marker and nothing for an empty map', () => {
    expect(fanOutMarkers([]).size).toBe(0);
    expect([...fanOutMarkers([point('a', 1, 1), point('b', 500, 500)]).keys()]).toEqual(['a', 'b']);
  });
});
