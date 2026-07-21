import {
  computeContainContentRect,
  computeCoverContentRect,
  containerPointToNormalizedRegion,
  normalizedRegionToContainerBox,
} from '../imageRegionCoords';
import { isValidClaimedRegion } from '@parkio/types';
import { useShareDraftStore } from '@/features/share/state/shareDraftStore';

jest.mock('@/features/share/prepareImage', () => ({
  deleteDraftPhoto: jest.fn(),
  draftPhotoExists: jest.fn(() => true),
}));

jest.mock('@/services/jsonStore', () => ({
  readJson: jest.fn(async () => null),
  writeJson: jest.fn(async () => undefined),
  removeJson: jest.fn(async () => undefined),
}));

/**
 * prepareImage uses ImageManipulator which applies EXIF orientation before
 * returning width/height. claimedRegion is always relative to those prepared dims.
 */
describe('imageRegionCoords / EXIF / objectFit', () => {
  it('maps EXIF 90° rotation via prepared landscape dims (not raw portrait file)', () => {
    // Raw capture: 3000x4000 portrait with Orientation=6 → prepared 1600x1200 landscape.
    const preparedW = 1600;
    const preparedH = 1200;
    const container = { w: 360, h: 480 };
    const content = computeContainContentRect(container.w, container.h, preparedW, preparedH);
    // Landscape in portrait container → letterbox top/bottom.
    expect(content.contentW).toBeCloseTo(360);
    expect(content.contentH).toBeCloseTo(270);
    expect(content.offsetY).toBeCloseTo(105);

    const region = containerPointToNormalizedRegion(
      { x: content.offsetX + content.contentW * 0.1, y: content.offsetY + content.contentH * 0.2 },
      { x: content.offsetX + content.contentW * 0.6, y: content.offsetY + content.contentH * 0.7 },
      content,
    );
    expect(region).not.toBeNull();
    expect(region!.x).toBeCloseTo(0.1, 2);
    expect(region!.y).toBeCloseTo(0.2, 2);
    expect(region!.width).toBeCloseTo(0.5, 2);
    expect(region!.height).toBeCloseTo(0.5, 2);
    expect(isValidClaimedRegion(region)).toBe(true);
  });

  it('maps EXIF 180° using prepared dims unchanged in aspect', () => {
    const preparedW = 1600;
    const preparedH = 1200;
    const content = computeContainContentRect(400, 300, preparedW, preparedH);
    expect(content.offsetX).toBeCloseTo(0);
    expect(content.offsetY).toBeCloseTo(0);
    const region = containerPointToNormalizedRegion(
      { x: 40, y: 30 },
      { x: 240, y: 210 },
      content,
    );
    expect(region!.x).toBeCloseTo(0.1, 2);
    expect(region!.y).toBeCloseTo(0.1, 2);
    expect(region!.width).toBeCloseTo(0.5, 2);
    expect(region!.height).toBeCloseTo(0.6, 2);
  });

  it('portrait image in landscape container letterboxes left/right (contain)', () => {
    const content = computeContainContentRect(640, 360, 900, 1600);
    expect(content.contentH).toBeCloseTo(360);
    expect(content.contentW).toBeCloseTo(360 * (900 / 1600));
    expect(content.offsetX).toBeGreaterThan(0);
    expect(content.offsetY).toBeCloseTo(0);

    // Touch in left letterbox padding must clamp into content — not invent off-image coords.
    const region = containerPointToNormalizedRegion(
      { x: 0, y: 10 },
      { x: content.offsetX + content.contentW * 0.8, y: content.offsetY + content.contentH * 0.8 },
      content,
    );
    expect(region).not.toBeNull();
    expect(region!.x).toBeGreaterThanOrEqual(0);
    expect(region!.x + region!.width).toBeLessThanOrEqual(1.0001);
  });

  it('cover content rect differs from contain (cropped) — annotator must use contain', () => {
    const contain = computeContainContentRect(400, 400, 1600, 900);
    const cover = computeCoverContentRect(400, 400, 1600, 900);
    expect(contain.contentW).toBeLessThan(cover.contentW);
    expect(cover.offsetX).toBeLessThan(0); // landscape cover crops horizontally in square
  });

  it('round-trips normalized region through container box', () => {
    const content = computeContainContentRect(360, 480, 1200, 1600);
    const original = { x: 0.15, y: 0.2, width: 0.4, height: 0.35 };
    const box = normalizedRegionToContainerBox(original, content);
    const back = containerPointToNormalizedRegion(
      { x: box.left, y: box.top },
      { x: box.left + box.width, y: box.top + box.height },
      content,
    );
    expect(back!.x).toBeCloseTo(original.x, 4);
    expect(back!.y).toBeCloseTo(original.y, 4);
    expect(back!.width).toBeCloseTo(original.width, 4);
    expect(back!.height).toBeCloseTo(original.height, 4);
  });

  it('replacing photo clears previous region', () => {
    useShareDraftStore.getState().reset();
    const store = useShareDraftStore.getState();
    store.setPhoto({ uri: 'file://a.jpg', width: 100, height: 100 });
    store.setClaimedRegion({ x: 0.1, y: 0.1, width: 0.4, height: 0.4 });
    expect(isValidClaimedRegion(useShareDraftStore.getState().photo?.claimedRegion)).toBe(true);

    store.setPhoto({ uri: 'file://b.jpg', width: 200, height: 150 });
    expect(useShareDraftStore.getState().photo?.claimedRegion).toBeNull();
  });

  it('reopening a draft preserves the correct region (hydrate path)', async () => {
    const { readJson } = jest.requireMock('@/services/jsonStore') as {
      readJson: jest.Mock;
    };
    useShareDraftStore.getState().reset();
    const region = { x: 0.2, y: 0.25, width: 0.45, height: 0.4 };
    readJson.mockResolvedValueOnce({
      step: 'location',
      photo: { uri: 'file://draft.jpg', width: 1600, height: 1200, claimedRegion: region },
      mediaId: 'm1',
      location: { latitude: 1, longitude: 2 },
      gpsAccuracy: 5,
      manualLocationEdited: false,
      addressText: '',
      description: '',
      vehicleTypes: [],
      parkingContext: 'STREET_PARKING',
      legalStatus: null,
      violationReasons: [],
      savedAt: new Date().toISOString(),
    });
    await useShareDraftStore.getState().hydrate();
    const photo = useShareDraftStore.getState().photo;
    expect(photo?.claimedRegion).toEqual(region);
    expect(photo?.width).toBe(1600);
    expect(photo?.height).toBe(1200);
  });
});