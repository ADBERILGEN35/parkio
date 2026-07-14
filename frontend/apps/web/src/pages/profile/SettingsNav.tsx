import { Icon, cn } from '@parkio/ui';
import { useTranslation } from 'react-i18next';

export interface SettingsSection {
  id: string;
  label: string;
  icon: string;
}

export interface SettingsNavProps {
  sections: readonly SettingsSection[];
  active: string;
  onSelect: (id: string) => void;
}

/**
 * Frontend-only section selector for `/profile` (no route changes). Renders as a
 * horizontal scrollable strip on mobile and a sticky vertical rail on desktop,
 * matching the design-system "In-page settings nav" (active = primary-container
 * pill + filled icon). Tabs only toggle which section card set is visible.
 */
export function SettingsNav({ sections, active, onSelect }: SettingsNavProps) {
  const { t } = useTranslation('settings');
  return (
    <nav
      aria-label={t('sections.navAria')}
      role="tablist"
      className="flex w-full min-w-0 gap-xs overflow-x-auto overscroll-x-contain pb-xs [-webkit-overflow-scrolling:touch] lg:sticky lg:top-lg lg:flex-col lg:overflow-visible lg:pb-0"
    >
      {sections.map((section) => {
        const isActive = section.id === active;
        return (
          <button
            key={section.id}
            type="button"
            role="tab"
            aria-selected={isActive}
            onClick={() => onSelect(section.id)}
            className={cn(
              'flex max-w-full shrink-0 items-center gap-sm rounded-xl px-md py-sm text-label-md font-semibold transition-colors duration-std sm:px-lg sm:py-md',
              isActive
                ? 'bg-primary-container text-on-primary-container shadow-soft'
                : 'text-on-surface-variant hover:bg-surface-container-low',
            )}
          >
            <Icon name={section.icon} filled={isActive} className="shrink-0 text-[20px] leading-none" />
            <span className="truncate">{section.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
