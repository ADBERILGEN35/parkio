import { fireEvent } from '@testing-library/react-native';
import { renderWithProviders } from '@/test/renderWithProviders';
import { MapAreaStatusSheet } from '../MapAreaStatusSheet';

describe('MapAreaStatusSheet', () => {
  it('renders collapsed initially with empty copy and share CTA', () => {
    const onShare = jest.fn();
    const { getByText, queryByText } = renderWithProviders(
      <MapAreaStatusSheet
        visible
        radiusLabel="500 m"
        level={1}
        lastRefreshedAt={new Date('2026-07-20T12:00:00Z')}
        onShare={onShare}
      />,
    );

    getByText('Bu bölgede henüz güncel boş yer bildirimi yok.');
    getByText('Yer paylaş');
    expect(queryByText('Bölge durumu')).toBeNull();

    fireEvent.press(getByText('Yer paylaş'));
    expect(onShare).toHaveBeenCalledTimes(1);
  });

  it('expands and collapses via the expand control', () => {
    const { getAllByLabelText, getByText, queryByText } = renderWithProviders(
      <MapAreaStatusSheet
        visible
        radiusLabel="1 km"
        level={2}
        lastRefreshedAt={new Date('2026-07-20T12:00:00Z')}
        onShare={() => undefined}
      />,
    );

    fireEvent.press(getAllByLabelText('Genişlet')[0]);
    getByText('Bölge durumu');
    getByText('Arama yarıçapı');
    getByText('1 km');

    fireEvent.press(getAllByLabelText('Daralt')[0]);
    expect(queryByText('Bölge durumu')).toBeNull();
  });

  it('respects session dismissal and stays hidden while remounted visible', () => {
    const { getByLabelText, queryByText, rerender } = renderWithProviders(
      <MapAreaStatusSheet
        visible
        radiusLabel={null}
        level={null}
        lastRefreshedAt={null}
        onShare={() => undefined}
      />,
    );

    fireEvent.press(getByLabelText('Kapat'));
    expect(queryByText('Bu bölgede henüz güncel boş yer bildirimi yok.')).toBeNull();

    rerender(
      <MapAreaStatusSheet
        visible={false}
        radiusLabel={null}
        level={null}
        lastRefreshedAt={null}
        onShare={() => undefined}
      />,
    );
    rerender(
      <MapAreaStatusSheet
        visible
        radiusLabel={null}
        level={null}
        lastRefreshedAt={null}
        onShare={() => undefined}
      />,
    );
    // Same component instance keeps dismissed=true for the screen session.
    expect(queryByText('Bu bölgede henüz güncel boş yer bildirimi yok.')).toBeNull();
  });

  it('hides when not visible (active map data)', () => {
    const { queryByText } = renderWithProviders(
      <MapAreaStatusSheet
        visible={false}
        radiusLabel="500 m"
        level={1}
        lastRefreshedAt={null}
        onShare={() => undefined}
      />,
    );
    expect(queryByText('Bu bölgede henüz güncel boş yer bildirimi yok.')).toBeNull();
  });
});
