import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import { MAX_FILE_SIZE_BYTES, PREPARE_MAX_EDGE } from '../../constants';
import { prepareImage } from '../prepareImage';
import { deleteTempFile, getFileSize } from '../fileSystem';

jest.mock('expo-image-manipulator', () => ({
  ImageManipulator: { manipulate: jest.fn() },
  SaveFormat: { JPEG: 'jpeg' },
}));

jest.mock('../fileSystem', () => ({
  deleteTempFile: jest.fn(),
  getFileSize: jest.fn(),
}));

describe('prepareImage', () => {
  const resize = jest.fn();
  const saveAsync = jest.fn();
  const renderAsync = jest.fn(async () => ({ saveAsync }));

  beforeEach(() => {
    jest.clearAllMocks();
    resize.mockReturnValue(undefined);
    saveAsync
      .mockResolvedValueOnce({ uri: 'file:///oversized.jpg', width: PREPARE_MAX_EDGE, height: 1536 })
      .mockResolvedValueOnce({ uri: 'file:///prepared.jpg', width: PREPARE_MAX_EDGE, height: 1536 });
    jest.mocked(getFileSize).mockReturnValueOnce(MAX_FILE_SIZE_BYTES + 1).mockReturnValueOnce(1024);
    jest.mocked(ImageManipulator.manipulate).mockReturnValue({ resize, renderAsync } as unknown as ReturnType<
      typeof ImageManipulator.manipulate
    >);
  });

  it('resizes the longest edge, re-encodes to JPEG, and deletes oversized intermediates', async () => {
    const prepared = await prepareImage({
      uri: 'file:///source.heic',
      width: 4032,
      height: 3024,
      mimeType: 'image/jpeg',
    });

    expect(ImageManipulator.manipulate).toHaveBeenCalledWith('file:///source.heic');
    expect(resize).toHaveBeenCalledWith({ width: PREPARE_MAX_EDGE });
    expect(saveAsync).toHaveBeenCalledWith({ compress: 0.7, format: SaveFormat.JPEG });
    expect(saveAsync.mock.calls[1][0].compress).toBeCloseTo(0.55);
    expect(saveAsync.mock.calls[1][0].format).toBe(SaveFormat.JPEG);
    expect(deleteTempFile).toHaveBeenCalledWith('file:///oversized.jpg');
    expect(prepared).toMatchObject({
      uri: 'file:///prepared.jpg',
      width: PREPARE_MAX_EDGE,
      height: 1536,
      fileSize: 1024,
      contentType: 'image/jpeg',
    });
  });
});
