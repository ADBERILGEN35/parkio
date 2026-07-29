import { Icon } from '@parkio/ui';
import { useTranslation } from 'react-i18next';

/** Dedicated moderator-authored note section — never mixed into AI reason copy. */
export function ModeratorNote({ note }: { note: string | null | undefined }) {
  const { t } = useTranslation('parking');
  if (!note?.trim()) {
    return null;
  }

  return (
    <section
      className="rounded-lg bg-tertiary-container/15 p-md ring-1 ring-tertiary/20"
      data-testid="moderator-note"
      aria-label={t('decision.moderatorNote.aria')}
    >
      <p className="m-0 flex items-center gap-xs text-label-sm font-semibold text-tertiary">
        <Icon name="sticky_note_2" className="text-[16px] leading-none" aria-hidden />
        {t('decision.moderatorNote.title')}
      </p>
      <blockquote className="m-0 mt-sm border-l-2 border-tertiary/40 pl-md text-body-md text-on-surface">
        {note.trim()}
      </blockquote>
    </section>
  );
}
