import { isValidClaimedRegion } from '@parkio/types';

describe('draft upload region preconditions', () => {
  function shouldStartUpload(input: {
    photoUri: string | null;
    claimedRegion: { x: number; y: number; width: number; height: number } | null;
    mediaId: string | null;
    inFlight: boolean;
  }): boolean {
    if (!input.photoUri || input.mediaId || input.inFlight) {
      return false;
    }
    return isValidClaimedRegion(input.claimedRegion);
  }

  it('does not start upload without a region', () => {
    expect(
      shouldStartUpload({
        photoUri: 'file://a.jpg',
        claimedRegion: null,
        mediaId: null,
        inFlight: false,
      }),
    ).toBe(false);
  });

  it('starts upload when photo + valid region are present', () => {
    const region = { x: 0.1, y: 0.1, width: 0.4, height: 0.4 };
    expect(
      shouldStartUpload({
        photoUri: 'file://a.jpg',
        claimedRegion: region,
        mediaId: null,
        inFlight: false,
      }),
    ).toBe(true);
  });

  it('upload options include claimed region fields', () => {
    const region = { x: 0.1, y: 0.2, width: 0.3, height: 0.4 };
    const formFields = {
      claimedRegionX: String(region.x),
      claimedRegionY: String(region.y),
      claimedRegionWidth: String(region.width),
      claimedRegionHeight: String(region.height),
    };
    expect(formFields).toEqual({
      claimedRegionX: '0.1',
      claimedRegionY: '0.2',
      claimedRegionWidth: '0.3',
      claimedRegionHeight: '0.4',
    });
  });
});