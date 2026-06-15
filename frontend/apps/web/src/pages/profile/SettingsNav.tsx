import { Icon, cn } from '@parkio/ui';

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
  return (
    <nav
      aria-label="Settings sections"
      role="tablist"
      aria-orientation="vertical"
      className="-mx-md flex gap-xs overflow-x-auto px-md pb-xs lg:sticky lg:top-lg lg:mx-0 lg:flex-col lg:overflow-visible lg:px-0 lg:pb-0"
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
              'flex shrink-0 items-center gap-sm rounded-xl px-lg py-md text-label-md font-semibold transition-colors duration-std',
              isActive
                ? 'bg-primary-container text-on-primary-container shadow-soft'
                : 'text-on-surface-variant hover:bg-surface-container-low',
            )}
          >
            <Icon name={section.icon} filled={isActive} className="text-[20px] leading-none" />
            {section.label}
          </button>
        );
      })}
    </nav>
  );
}
