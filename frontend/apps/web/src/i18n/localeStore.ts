import type { ParkioLocale } from '@parkio/types';
import i18n from 'i18next';
import { create } from 'zustand';
import { applyDocumentLocale, resolveInitialLocale, writeStoredLocale } from './locale-storage';

interface LocaleState {
  locale: ParkioLocale;
  /** Switch UI language immediately and persist to localStorage. */
  setLocale: (locale: ParkioLocale) => void;
  /**
   * Apply a server preference without treating it as a fresh user choice
   * unless it differs from the current locale (still writes local storage so
   * anonymous revisit after logout keeps the last authenticated choice).
   */
  syncFromServer: (locale: ParkioLocale) => void;
}

function applyLocale(locale: ParkioLocale): void {
  writeStoredLocale(locale);
  applyDocumentLocale(locale);
  void i18n.changeLanguage(locale);
}

export const useLocaleStore = create<LocaleState>((set, get) => ({
  locale: resolveInitialLocale(),

  setLocale(locale) {
    if (get().locale === locale && i18n.language === locale) return;
    applyLocale(locale);
    set({ locale });
  },

  syncFromServer(locale) {
    if (get().locale === locale && i18n.language === locale) return;
    applyLocale(locale);
    set({ locale });
  },
}));
