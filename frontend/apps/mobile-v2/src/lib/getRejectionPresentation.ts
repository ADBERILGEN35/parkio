import type { SpotRejection } from '@parkio/types';
import type { Translator } from '@/i18n/LocaleProvider';
import type { TranslationKey } from '@/i18n/translations';

export type RejectionPresentationVariant =
  | 'AI_POLICY'
  | 'MODERATOR'
  | 'SYSTEM_MIGRATION'
  | 'UNKNOWN';

export type RejectionPresentationTone = 'danger' | 'warning' | 'neutral';

export interface RejectionPresentation {
  variant: RejectionPresentationVariant;
  title: string;
  /** Display-only status label when domain status stays REJECTED but semantics differ. */
  displayStatus: string | null;
  message: string;
  sourceLabel: string;
  tone: RejectionPresentationTone;
  showTechnicalMetadata: boolean;
  moderatorNote: string | null;
  code: string | null;
}

const KNOWN_CODES = new Set([
  'LEGACY_POLICY_RESET',
  'CLEARLY_UNRELATED_CONTENT',
  'INDOOR_SCENE',
  'SELFIE_OR_PERSONAL_PHOTO',
  'FOOD_OR_RANDOM_OBJECT',
  'SCREENSHOT_OR_DOCUMENT',
  'NO_ROAD_OR_PARKING_CONTEXT',
  'UNUSABLE_IMAGE',
  'IMAGE_TOO_DARK',
  'IMAGE_TOO_BLURRY',
  'IMAGE_CORRUPTED',
  'LEGALITY_CONCERN',
  'DUPLICATE_SUBMISSION',
  'MANUAL_MODERATOR_REJECTION',
  'OTHER',
]);

export interface RejectionPresentationInput {
  status?: string | null;
  code?: string | null;
  source?: string | null;
  serverMessage?: string | null;
  moderatorNote?: string | null;
  t: Translator;
}

/**
 * Single presentation mapper for parking-spot rejection UI.
 * Product {@code code} + i18n own standard copy; server message is fallback only.
 */
export function getRejectionPresentation(input: RejectionPresentationInput): RejectionPresentation {
  const { t } = input;
  const code = input.code?.trim() || null;
  const source = input.source?.trim() || null;
  const variant = resolveVariant(code, source);

  const title = translateOr(
    t,
    `rejection.titleByVariant.${variant}` as TranslationKey,
    'rejection.title',
  );
  const sourceLabel = resolveSourceLabel(t, source, variant);
  const localizedMessage = resolveMessage(code, input.serverMessage, t);
  const moderatorNote = resolveModeratorNote(
    variant,
    input.moderatorNote,
    input.serverMessage,
    code,
    t,
  );

  const displayStatus =
    variant === 'SYSTEM_MIGRATION' && (input.status === 'REJECTED' || !input.status)
      ? t('rejection.displayStatus.systemMigration')
      : null;

  return {
    variant,
    title,
    displayStatus,
    message: localizedMessage,
    sourceLabel,
    tone:
      variant === 'SYSTEM_MIGRATION' ? 'neutral' : variant === 'MODERATOR' ? 'warning' : 'danger',
    showTechnicalMetadata: true,
    moderatorNote,
    code,
  };
}

export function isLegacySystemMigrationRejection(
  rejection: Pick<SpotRejection, 'code' | 'source'> | null | undefined,
): boolean {
  if (!rejection) return false;
  return rejection.code === 'LEGACY_POLICY_RESET' || rejection.source === 'SYSTEM_MIGRATION';
}

function resolveVariant(code: string | null, source: string | null): RejectionPresentationVariant {
  if (code === 'LEGACY_POLICY_RESET' || source === 'SYSTEM_MIGRATION') {
    return 'SYSTEM_MIGRATION';
  }
  if (source === 'MODERATOR' || code === 'MANUAL_MODERATOR_REJECTION') {
    return 'MODERATOR';
  }
  if (source === 'AI_POLICY') {
    return 'AI_POLICY';
  }
  return 'UNKNOWN';
}

function resolveSourceLabel(
  t: Translator,
  source: string | null,
  variant: RejectionPresentationVariant,
): string {
  if (source === 'AI_POLICY' || source === 'MODERATOR' || source === 'SYSTEM_MIGRATION') {
    return t(`rejection.source.${source}` as TranslationKey);
  }
  return t(`rejection.sourceByVariant.${variant}` as TranslationKey);
}

function resolveMessage(
  code: string | null,
  serverMessage: string | null | undefined,
  t: Translator,
): string {
  if (code && KNOWN_CODES.has(code)) {
    const key = `rejection.codes.${code}` as TranslationKey;
    const fromI18n = t(key);
    if (fromI18n && fromI18n !== key) {
      return fromI18n;
    }
  }
  if (serverMessage && serverMessage.trim()) {
    return serverMessage.trim();
  }
  return t('rejection.codes.UNKNOWN');
}

function resolveModeratorNote(
  variant: RejectionPresentationVariant,
  moderatorNote: string | null | undefined,
  serverMessage: string | null | undefined,
  code: string | null,
  t: Translator,
): string | null {
  if (variant !== 'MODERATOR') {
    return null;
  }
  if (moderatorNote && moderatorNote.trim()) {
    return moderatorNote.trim();
  }
  // Back-compat: older payloads stored the author note in message only.
  if (!serverMessage?.trim() || !code) {
    return null;
  }
  const catalogKey = `rejection.codes.${code}` as TranslationKey;
  const catalogMessage = KNOWN_CODES.has(code) ? t(catalogKey) : '';
  if (catalogMessage && catalogMessage !== catalogKey && serverMessage.trim() === catalogMessage) {
    return null;
  }
  if (KNOWN_CODES.has(code) && catalogMessage && catalogMessage !== catalogKey && serverMessage.trim() !== catalogMessage) {
    return serverMessage.trim();
  }
  return null;
}

function translateOr(t: Translator, key: TranslationKey, fallback: TranslationKey): string {
  const value = t(key);
  return value === key ? t(fallback) : value;
}
