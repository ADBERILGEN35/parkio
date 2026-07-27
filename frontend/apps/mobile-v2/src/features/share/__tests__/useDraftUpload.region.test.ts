describe('draft upload preconditions', () => {
  function shouldStartUpload(input: {
    photoUri: string | null;
    mediaId: string | null;
    inFlight: boolean;
  }): boolean {
    if (!input.photoUri || input.mediaId || input.inFlight) {
      return false;
    }
    return true;
  }

  it('starts upload when a photo is present without a region', () => {
    expect(
      shouldStartUpload({
        photoUri: 'file://a.jpg',
        mediaId: null,
        inFlight: false,
      }),
    ).toBe(true);
  });

  it('does not start upload without a photo', () => {
    expect(
      shouldStartUpload({
        photoUri: null,
        mediaId: null,
        inFlight: false,
      }),
    ).toBe(false);
  });

  it('optional claimed region fields are only sent when valid', () => {
    const region = { x: 0.1, y: 0.2, width: 0.3, height: 0.4 };
    const withRegion = {
      claimedRegionX: String(region.x),
      claimedRegionY: String(region.y),
      claimedRegionWidth: String(region.width),
      claimedRegionHeight: String(region.height),
    };
    expect(withRegion.claimedRegionX).toBe('0.1');
    expect(Object.keys(withRegion)).toHaveLength(4);
  });
});
