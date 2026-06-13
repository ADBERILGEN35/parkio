import { Icon } from '@parkio/ui';

/** Brand lockup shown above the auth cards — wordmark + concierge tagline. */
export function AuthBrand() {
  return (
    <div className="flex flex-col items-center gap-xs text-center">
      <span className="flex items-center gap-xs text-headline-md font-bold text-primary">
        <Icon name="local_parking" filled className="text-[28px] leading-none" />
        Parkio
      </span>
      <p className="m-0 text-label-sm text-on-surface-variant">Concierge for the curb.</p>
    </div>
  );
}
