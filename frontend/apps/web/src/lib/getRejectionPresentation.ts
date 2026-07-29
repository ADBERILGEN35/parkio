import type { SpotRejection } from '@parkio/types';
import type { BadgeTone } from '@parkio/ui';
import type { TFunction } from 'i18next';

export type RejectionPresentationVariant = 'AI_POLICY' | 'MODERATOR' | 'SYSTEM_MIGRATION' | 'UNKNOWN';

export interface RejectionPresentation {
  variant: RejectionPresentationVariant;
  title: string;
  /** Display-only status label when domain status stays REJECTED but semantics differ. */
  displayStatus: string | null;
  message: string;
  sourceLabel: string;
  tone: BadgeTone;
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
  t: TFunction;
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

  const title = t(`rejection.titleByVariant.${variant}`, {
    defaultValue: t('rejection.title'),
  });
  const sourceLabel = t(`rejection.source.${source ?? variant}`, {
    defaultValue: t(`rejection.sourceByVariant.${variant}`, {
      defaultValue: source ?? variant,
    }),
  });

  const localizedMessage = resolveMessage(code, input.serverMessage, t);
  const moderatorNote = resolveModeratorNote(variant, input.moderatorNote, input.serverMessage, code, t);

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
    tone: variant === 'SYSTEM_MIGRATION' ? 'neutral' : variant === 'MODERATOR' ? 'warning' : 'danger',
    showTechnicalMetadata: true,
    moderatorNote,
    code,
  };
}

export function isLegacySystemMigrationRejection(
  rejection: Pick<SpotRejection, 'code' | 'source'> | null | undefined,
): boolean {
  if (!rejection) return false;
  return (
    rejection.code === 'LEGACY_POLICY_RESET' || rejection.source === 'SYSTEM_MIGRATION'
  );
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

function resolveMessage(code: string | null, serverMessage: string | null | undefined, t: TFunction): string {
  if (code && KNOWN_CODES.has(code)) {
    const fromI18n = t(`rejection.codes.${code}`, { defaultValue: '' });
    if (fromI18n && fromI18n !== `rejection.codes.${code}`) {
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
  t: TFunction,
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
  const catalogMessage = t(`rejection.codes.${code}`, { defaultValue: '' });
  if (catalogMessage && serverMessage.trim() === catalogMessage) {
    return null;
  }
  // If i18n resolved the primary message from code, and server message differs, treat as note.
  if (KNOWN_CODES.has(code) && catalogMessage && serverMessage.trim() !== catalogMessage) {
    return serverMessage.trim();
  }
  return null;
}
