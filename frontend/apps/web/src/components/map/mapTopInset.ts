import type { RefObject } from 'react';
import { FAN_OUT_GAP, FAN_OUT_MARKER_SIZE } from './markerFanOut';

/** An element drawn over the top of the map, such as Public Explore's search and summary stack. */
export type MapTopOverlayRef = RefObject<HTMLElement | null>;

/** Room a marker needs below the overlay: half its height, plus the gap. */
export const TOP_OVERLAY_CLEARANCE = FAN_OUT_MARKER_SIZE / 2 + FAN_OUT_GAP;

/**
 * How far the overlay reaches into the map, in pixels from the map's top edge, measured now: its height
 * changes with its content (a summary, an alert). 0 without an overlay.
 */
export function mapTopInset(container: HTMLElement, overlay: MapTopOverlayRef | undefined): number {
  const element = overlay?.current;
  if (!element) return 0;
  const inset = element.getBoundingClientRect().bottom - container.getBoundingClientRect().top;
  return Number.isFinite(inset) ? Math.max(0, Math.round(inset)) : 0;
}
