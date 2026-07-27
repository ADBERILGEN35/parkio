jest.mock('expo-image-manipulator', () => ({
  SaveFormat: { JPEG: 'jpeg' },
  ImageManipulator: {
    manipulate: jest.fn(() => ({
      resize: jest.fn().mockReturnThis(),
      renderAsync: jest.fn(async () => ({
        saveAsync: jest.fn(async () => ({
          uri: '/cache/prepared-temp.jpg',
          width: 800,
          height: 600,
        })),
        release: jest.fn(),
      })),
    })),
  },
}));

import { File, Paths } from 'expo-file-system';
import { prepareImage, resetDraftPhotoCopySeq, isAppOwnedDraftPhotoUri } from '../prepareImage';

describe('prepareImage', () => {
  beforeEach(() => {
    resetDraftPhotoCopySeq();
    new File('/cache/prepared-temp.jpg').write('jpeg-bytes');
  });

  it('writes each prepared photo to a unique durable uri', async () => {
    const first = await prepareImage({ uri: 'file://source-a.jpg', width: 2000, height: 1500 });
    const second = await prepareImage({ uri: 'file://source-b.jpg', width: 2000, height: 1500 });

    expect(first.uri).not.toBe(second.uri);
    expect(isAppOwnedDraftPhotoUri(first.uri)).toBe(true);
    expect(isAppOwnedDraftPhotoUri(second.uri)).toBe(true);
    expect(new File(first.uri).exists).toBe(true);
    expect(new File(second.uri).exists).toBe(true);
    expect(first.uri).toContain(`${Paths.document}/parkio-store/draft-photo-`);
  });
});