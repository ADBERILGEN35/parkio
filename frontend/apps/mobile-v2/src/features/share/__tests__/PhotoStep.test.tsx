import { waitFor } from '@testing-library/react-native';
import { renderWithProviders } from '@/test/renderWithProviders';
import { PhotoStep } from '../steps/PhotoStep';
import { useShareDraftStore } from '../state/shareDraftStore';

describe('PhotoStep preview source', () => {
  beforeEach(() => {
    useShareDraftStore.getState().reset();
  });

  it('updates the preview uri when the draft photo revision changes', async () => {
    useShareDraftStore.getState().setPhoto({
      uri: '/doc/parkio-store/draft-photo-1.jpg',
      width: 100,
      height: 80,
    });

    const { getByTestId } = renderWithProviders(
      <PhotoStep onRetake={() => {}} onPickGallery={() => {}} onCancelUpload={() => {}} onRetryUpload={() => {}} />,
    );

    expect(getByTestId('share-draft-photo').props.source.uri).toBe('/doc/parkio-store/draft-photo-1.jpg');

    useShareDraftStore.getState().setPhoto({
      uri: '/doc/parkio-store/draft-photo-2.jpg',
      width: 120,
      height: 90,
    });

    await waitFor(() => {
      expect(getByTestId('share-draft-photo').props.source.uri).toBe('/doc/parkio-store/draft-photo-2.jpg');
    });
  });
});