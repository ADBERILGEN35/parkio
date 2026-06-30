import { MAX_IMAGE_PIXELS } from '../../constants';
import { validateLocalAsset } from '../validation';

describe('validateLocalAsset', () => {
  it('accepts camera captures without a reported mime type or file size', () => {
    expect(validateLocalAsset({ uri: 'file:///capture.jpg', width: 1200, height: 900 })).toEqual({ ok: true });
  });

  it('rejects explicit unsupported source mime types before upload', () => {
    expect(
      validateLocalAsset({
        uri: 'file:///doc.gif',
        width: 800,
        height: 600,
        mimeType: 'image/gif',
      }),
    ).toMatchObject({ ok: false, reason: 'unsupported-type' });
  });

  it('rejects images beyond the backend pixel ceiling', () => {
    const width = MAX_IMAGE_PIXELS + 1;
    expect(validateLocalAsset({ uri: 'file:///huge.jpg', width, height: 1 })).toMatchObject({
      ok: false,
      reason: 'too-many-pixels',
    });
  });
});
