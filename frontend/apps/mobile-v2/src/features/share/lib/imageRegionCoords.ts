import type { ClaimedRegion } from '@parkio/types';
import { isValidClaimedRegion } from '@parkio/types';

/** Axis-aligned content rect of the bitmap inside a container (contain/cover). */
export interface ContentRect {
  offsetX: number;
  offsetY: number;
  contentW: number;
  contentH: number;
}

export interface Point {
  x: number;
  y: number;
}

export interface PixelBox {
  left: number;
  top: number;
  width: number;
  height: number;
}

/**
 * claimedRegion is always relative to the orientation-normalized prepared JPEG
 * (prepareImage / ImageManipulator applies EXIF orientation before resize).
 * UI mapping must use prepared width/height, not the raw camera file dims.
 */
export function computeContainContentRect(
  containerW: number,
  containerH: number,
  imageW: number,
  imageH: number,
): ContentRect {
  const cw = Math.max(containerW, 1);
  const ch = Math.max(containerH, 1);
  const iw = Math.max(imageW, 1);
  const ih = Math.max(imageH, 1);
  const scale = Math.min(cw / iw, ch / ih);
  const contentW = iw * scale;
  const contentH = ih * scale;
  return {
    offsetX: (cw - contentW) / 2,
    offsetY: (ch - contentH) / 2,
    contentW,
    contentH,
  };
}

export function computeCoverContentRect(
  containerW: number,
  containerH: number,
  imageW: number,
  imageH: number,
): ContentRect {
  const cw = Math.max(containerW, 1);
  const ch = Math.max(containerH, 1);
  const iw = Math.max(imageW, 1);
  const ih = Math.max(imageH, 1);
  const scale = Math.max(cw / iw, ch / ih);
  const contentW = iw * scale;
  const contentH = ih * scale;
  return {
    offsetX: (cw - contentW) / 2,
    offsetY: (ch - contentH) / 2,
    contentW,
    contentH,
  };
}

function clamp(n: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, n));
}

/** Map a drag in container coords onto a normalized region in image content space. */
export function containerPointToNormalizedRegion(
  start: Point,
  end: Point,
  content: ContentRect,
): ClaimedRegion | null {
  if (content.contentW <= 0 || content.contentH <= 0) {
    return null;
  }
  const leftBound = content.offsetX;
  const topBound = content.offsetY;
  const rightBound = content.offsetX + content.contentW;
  const bottomBound = content.offsetY + content.contentH;

  const x0 = clamp(start.x, leftBound, rightBound);
  const y0 = clamp(start.y, topBound, bottomBound);
  const x1 = clamp(end.x, leftBound, rightBound);
  const y1 = clamp(end.y, topBound, bottomBound);

  const left = Math.min(x0, x1);
  const top = Math.min(y0, y1);
  const width = Math.abs(x1 - x0);
  const height = Math.abs(y1 - y0);
  if (width <= 0 || height <= 0) {
    return null;
  }

  const region: ClaimedRegion = {
    x: (left - content.offsetX) / content.contentW,
    y: (top - content.offsetY) / content.contentH,
    width: width / content.contentW,
    height: height / content.contentH,
  };
  return isValidClaimedRegion(region) ? region : null;
}

/** Draw overlay box in container coords from a normalized image region. */
export function normalizedRegionToContainerBox(
  region: ClaimedRegion,
  content: ContentRect,
): PixelBox {
  return {
    left: content.offsetX + region.x * content.contentW,
    top: content.offsetY + region.y * content.contentH,
    width: region.width * content.contentW,
    height: region.height * content.contentH,
  };
}