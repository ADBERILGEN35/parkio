import { cn } from '@parkio/ui';

const MARK_SRC = '/brand/parkio-logo-mark.png';

/**
 * Official Parkio logo mark (P + car). Pair with visible "Parkio" wordmark text
 * and use empty alt so the accessible name is not duplicated.
 */
export function BrandMark({
  size = 28,
  className,
}: {
  size?: number;
  className?: string;
}) {
  return (
    <img
      src={MARK_SRC}
      alt=""
      width={size}
      height={size}
      decoding="async"
      className={cn('block shrink-0 object-contain', className)}
    />
  );
}
