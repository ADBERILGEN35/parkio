import * as SecureStore from 'expo-secure-store';
import { act, renderHook, waitFor } from '@testing-library/react-native';
import type { ReactNode } from 'react';
import { LocaleProvider, useLocale } from '../LocaleProvider';

function wrapper({ children }: { children: ReactNode }) {
  return <LocaleProvider>{children}</LocaleProvider>;
}

describe('LocaleProvider', () => {
  beforeEach(async () => {
    await SecureStore.deleteItemAsync('parkio.locale');
  });

  it('starts in canonical Turkish and switches immediately to English', async () => {
    const { result } = renderHook(useLocale, { wrapper });

    expect(result.current.locale).toBe('tr');
    expect(result.current.t('Home')).toBe('Ana Sayfa');

    act(() => result.current.setLocale('en'));

    expect(result.current.locale).toBe('en');
    expect(result.current.t('Home')).toBe('Home');
    await waitFor(() =>
      expect(SecureStore.setItemAsync).toHaveBeenCalledWith('parkio.locale', 'en'),
    );
  });

  it('hydrates a persisted English preference', async () => {
    await SecureStore.setItemAsync('parkio.locale', 'en');
    const { result } = renderHook(useLocale, { wrapper });

    await waitFor(() => expect(result.current.locale).toBe('en'));
  });
});

