import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import { normalizeLocale, type ParkioLocale } from '@parkio/types';
import { DEFAULT_NS, FALLBACK_LOCALE, I18N_NAMESPACES } from './config';
import { applyDocumentLocale, resolveInitialLocale } from './locale-storage';

import enAdmin from './locales/en/admin.json';
import enAnalytics from './locales/en/analytics.json';
import enAuth from './locales/en/auth.json';
import enCommon from './locales/en/common.json';
import enErrors from './locales/en/errors.json';
import enLegal from './locales/en/legal.json';
import enMap from './locales/en/map.json';
import enMedia from './locales/en/media.json';
import enModeration from './locales/en/moderation.json';
import enNavigation from './locales/en/navigation.json';
import enParking from './locales/en/parking.json';
import enSettings from './locales/en/settings.json';
import enValidation from './locales/en/validation.json';

import trAdmin from './locales/tr/admin.json';
import trAnalytics from './locales/tr/analytics.json';
import trAuth from './locales/tr/auth.json';
import trCommon from './locales/tr/common.json';
import trErrors from './locales/tr/errors.json';
import trLegal from './locales/tr/legal.json';
import trMap from './locales/tr/map.json';
import trMedia from './locales/tr/media.json';
import trModeration from './locales/tr/moderation.json';
import trNavigation from './locales/tr/navigation.json';
import trParking from './locales/tr/parking.json';
import trSettings from './locales/tr/settings.json';
import trValidation from './locales/tr/validation.json';

const resources = {
  tr: {
    common: trCommon,
    auth: trAuth,
    navigation: trNavigation,
    settings: trSettings,
    map: trMap,
    parking: trParking,
    media: trMedia,
    moderation: trModeration,
    analytics: trAnalytics,
    admin: trAdmin,
    errors: trErrors,
    validation: trValidation,
    legal: trLegal,
  },
  en: {
    common: enCommon,
    auth: enAuth,
    navigation: enNavigation,
    settings: enSettings,
    map: enMap,
    parking: enParking,
    media: enMedia,
    moderation: enModeration,
    analytics: enAnalytics,
    admin: enAdmin,
    errors: enErrors,
    validation: enValidation,
    legal: enLegal,
  },
} as const;

let initialized = false;

/** Initialize i18next once (safe to call from bootstrap and test setup). */
export async function initI18n(explicitLocale?: ParkioLocale | null): Promise<typeof i18n> {
  const locale = resolveInitialLocale(explicitLocale);

  if (!initialized) {
    await i18n.use(initReactI18next).init({
      resources,
      lng: locale,
      fallbackLng: FALLBACK_LOCALE,
      defaultNS: DEFAULT_NS,
      ns: [...I18N_NAMESPACES],
      interpolation: { escapeValue: false },
      returnNull: false,
      returnEmptyString: false,
      // Surface missing keys in non-production builds.
      saveMissing: import.meta.env.DEV,
      missingKeyHandler: import.meta.env.DEV
        ? (_lngs, ns, key) => {
            console.warn(`[i18n] Missing key: ${ns}:${key}`);
          }
        : undefined,
    });
    i18n.on('languageChanged', (lng) => {
      applyDocumentLocale(normalizeLocale(lng));
    });
    initialized = true;
  } else if (i18n.language !== locale) {
    await i18n.changeLanguage(locale);
  }

  applyDocumentLocale(locale);
  return i18n;
}

export default i18n;
