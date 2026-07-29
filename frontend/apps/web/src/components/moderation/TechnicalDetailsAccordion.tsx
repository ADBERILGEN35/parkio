import { Icon } from '@parkio/ui';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';

export type TechnicalDetailRow = {
  label: string;
  value: string;
  mono?: boolean;
};

/**
 * Collapsed-by-default technical evidence accordion.
 * Never render prompts or stack traces — callers must filter.
 */
export function TechnicalDetailsAccordion({ rows }: { rows: TechnicalDetailRow[] }) {
  const { t } = useTranslation('parking');
  const panelId = useId();
  const buttonId = useId();
  const [open, setOpen] = useState(false);
  const visible = rows.filter((row) => row.value.trim().length > 0);
  if (visible.length === 0) {
    return null;
  }

  return (
    <div className="rounded-lg ring-1 ring-outline-variant/40" data-testid="technical-details">
      <button
        id={buttonId}
        type="button"
        className="flex w-full items-center justify-between gap-sm rounded-lg bg-transparent px-md py-sm text-left text-label-sm font-semibold text-on-surface hover:bg-surface-container-high/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((value) => !value)}
      >
        <span className="inline-flex items-center gap-xs">
          <Icon name="terminal" className="text-[16px] leading-none" aria-hidden />
          {t('decision.technical.title')}
        </span>
        <Icon
          name={open ? 'expand_less' : 'expand_more'}
          className="text-[18px] leading-none text-on-surface-variant"
          aria-hidden
        />
      </button>
      {open ? (
        <div
          id={panelId}
          role="region"
          aria-labelledby={buttonId}
          className="border-t border-outline-variant/30 px-md py-sm"
        >
          <dl className="m-0 grid grid-cols-1 gap-sm sm:grid-cols-2">
            {visible.map((row) => (
              <div key={row.label}>
                <dt className="m-0 text-label-sm text-on-surface-variant">{row.label}</dt>
                <dd
                  className={`m-0 mt-xs break-all text-body-md text-on-surface ${row.mono ? 'font-mono text-label-sm' : ''}`}
                >
                  {row.value}
                </dd>
              </div>
            ))}
          </dl>
        </div>
      ) : null}
    </div>
  );
}
