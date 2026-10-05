/**
 * Fan-out layout for map markers that would cover one another (Asana 1219147334320125, owner decision
 * 2026-10-05: "Approve fan-out on /explore only").
 *
 * Markers whose boxes would touch on screen form a group. The group's markers are drawn on a small ring
 * around the group's centre, in their input order, so every marker stays a separate, fully visible
 * target. Markers at identical coordinates always form a group, at every zoom. A marker that touches no
 * other one stays where it is.
 *
 * Only the drawn position changes: the result is a pixel offset per marker. Coordinates are never
 * changed, and the caller shows each moved marker's true location.
 */

/** A marker's true position on screen, in pixels. */
export interface ScreenPoint {
  id: string;
  x: number;
  y: number;
}

/** The visible map area, in the same pixels. A ring is kept inside it, below `top`. */
export interface FanOutBounds {
  width: number;
  height: number;
  /** Where the visible map starts, below controls drawn over its top edge. */
  top: number;
}

/** The facility markers are 40×40 px buttons (`MunicipalFacilityMarker`). */
export const FAN_OUT_MARKER_SIZE = 40;
/**
 * Space kept between two markers, so neither covers any part of the other. It also takes a selected
 * marker's growth (scale 1.1: 2 px a side) and the 2 px lift on hover.
 */
export const FAN_OUT_GAP = 6;

type Offset = [number, number];

interface Position {
  id: string;
  x: number;
  y: number;
}

/** Two square markers overlap, or come closer than the gap, when both axes are within one pitch. */
function collide(a: Position, b: Position, pitch: number): boolean {
  return Math.abs(a.x - b.x) < pitch && Math.abs(a.y - b.y) < pitch;
}

/**
 * Ring positions for `members`, around their mean position. The ring starts at a flat top and runs
 * clockwise in member order. Its radius grows until no two members collide.
 */
function ringFor(
  members: readonly ScreenPoint[],
  size: number,
  gap: number,
  bounds: FanOutBounds | undefined,
): Position[] {
  const pitch = size + gap;
  const count = members.length;
  const cx = members.reduce((sum, member) => sum + member.x, 0) / count;
  const cy = members.reduce((sum, member) => sum + member.y, 0) / count;
  const start = -Math.PI / 2 - Math.PI / count;
  let radius = pitch / (2 * Math.sin(Math.PI / count));
  let ring: Position[] = [];
  for (let attempt = 0; attempt < 64; attempt++) {
    ring = members.map((member, index) => {
      const angle = start + (2 * Math.PI * index) / count;
      return { id: member.id, x: radius * Math.cos(angle), y: radius * Math.sin(angle) };
    });
    const clear = ring.every((a, i) => ring.every((b, j) => j <= i || !collide(a, b, pitch)));
    if (clear) break;
    radius += 1;
  }
  // Keep the ring inside the visible map while its centre is on screen: a group at an edge, or under the
  // controls at the top, is drawn just inside instead.
  let centreX = cx;
  let centreY = cy;
  if (bounds && cx >= 0 && cx <= bounds.width && cy >= bounds.top && cy <= bounds.height) {
    const half = size / 2 + gap;
    const xs = ring.map((position) => position.x);
    const ys = ring.map((position) => position.y);
    const minX = half - Math.min(...xs);
    const maxX = bounds.width - half - Math.max(...xs);
    const minY = bounds.top + half - Math.min(...ys);
    const maxY = bounds.height - half - Math.max(...ys);
    if (minX <= maxX) centreX = Math.min(Math.max(cx, minX), maxX);
    if (minY <= maxY) centreY = Math.min(Math.max(cy, minY), maxY);
  }
  return ring.map((position) => ({ id: position.id, x: centreX + position.x, y: centreY + position.y }));
}

/**
 * The pixel offset of each marker, by id: `[0, 0]` for a marker that collides with nothing.
 * The input order is the order on the ring, so the same input always gives the same layout.
 */
export function fanOutMarkers(
  points: readonly ScreenPoint[],
  bounds?: FanOutBounds,
  size: number = FAN_OUT_MARKER_SIZE,
  gap: number = FAN_OUT_GAP,
): Map<string, Offset> {
  const pitch = size + gap;
  const order = new Map(points.map((point, index) => [point.id, index]));
  let groups: ScreenPoint[][] = points.map((point) => [point]);
  let layout: Position[][] = [];
  // Lay out every group, then merge any two groups whose markers collide, until none do. Each merge
  // removes a group, so this ends after at most points.length rounds.
  for (let round = 0; round <= points.length; round++) {
    layout = groups.map((group) =>
      group.length === 1 ? [{ id: group[0].id, x: group[0].x, y: group[0].y }] : ringFor(group, size, gap, bounds),
    );
    let merged = false;
    for (let i = 0; i < groups.length && !merged; i++) {
      for (let j = i + 1; j < groups.length && !merged; j++) {
        if (layout[i].some((a) => layout[j].some((b) => collide(a, b, pitch)))) {
          const union = [...groups[i], ...groups[j]].sort(
            (a, b) => (order.get(a.id) ?? 0) - (order.get(b.id) ?? 0),
          );
          groups = [...groups.slice(0, i), union, ...groups.slice(i + 1, j), ...groups.slice(j + 1)];
          merged = true;
        }
      }
    }
    if (!merged) break;
  }
  const offsets = new Map<string, Offset>();
  const truth = new Map(points.map((point) => [point.id, point]));
  for (const group of layout) {
    for (const position of group) {
      const point = truth.get(position.id);
      if (!point) continue;
      offsets.set(position.id, [Math.round(position.x - point.x), Math.round(position.y - point.y)]);
    }
  }
  return offsets;
}
