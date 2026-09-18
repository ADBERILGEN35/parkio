import { Icon } from '@parkio/ui';
import { useTranslation } from 'react-i18next';

export type AssistantEntryControlProps = {
  onOpen: () => void;
  compact?: boolean;
};

/** Primary map entry: “Nereye gidiyorsun?” — distinct from area discovery search. */
export function AssistantEntryControl({ onOpen, compact = false }: AssistantEntryControlProps) {
  const { t } = useTranslation('map');

  if (compact) {
    return (
      <button
        type="button"
        data-testid="assistant-entry"
        aria-label={t('assistant.entryAria')}
        onClick={onOpen}
        className="flex h-11 min-w-0 flex-1 items-center gap-xs rounded-full bg-surface-container px-md text-left text-label-md text-on-surface-variant transition-colors hover:bg-surface-container-high focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
      >
        <Icon name="assistant_navigation" className="shrink-0 text-[18px] leading-none text-primary" />
        <span className="truncate">{t('assistant.entryPrompt')}</span>
      </button>
    );
  }

  return (
    <button
      type="button"
      data-testid="assistant-entry"
      aria-label={t('assistant.entryAria')}
      onClick={onOpen}
      className="mt-md flex w-full items-center gap-sm rounded-2xl border border-outline-variant/40 bg-surface-container-lowest px-md py-md text-left transition-colors hover:bg-surface-container focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
        <Icon name="assistant_navigation" className="text-[22px] leading-none" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-title-sm text-on-surface">{t('assistant.entryPrompt')}</span>
        <span className="mt-0.5 block text-label-sm text-on-surface-variant">
          {t('assistant.entryHint')}
        </span>
      </span>
      <Icon name="chevron_right" className="shrink-0 text-[20px] text-on-surface-variant" />
    </button>
  );
}
