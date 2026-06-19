import type { ButtonHTMLAttributes, HTMLAttributes, LiHTMLAttributes, ReactNode } from 'react';
import { cn } from '@parkio/ui';

interface ProductCardBaseProps {
  selected?: boolean;
  interactive?: boolean;
  children: ReactNode;
}

type ProductCardDivProps = ProductCardBaseProps &
  HTMLAttributes<HTMLDivElement> & {
    as?: 'div';
  };

type ProductCardListItemProps = ProductCardBaseProps &
  LiHTMLAttributes<HTMLLIElement> & {
    as: 'li';
  };

export type ProductCardProps = ProductCardDivProps | ProductCardListItemProps;

const CARD_BASE =
  'rounded-2xl border bg-surface-container-lowest p-md shadow-soft transition-colors duration-std';

export function ProductCard({
  as = 'div',
  selected = false,
  interactive = false,
  className,
  children,
  ...props
}: ProductCardProps) {
  const classes = cn(
    CARD_BASE,
    selected ? 'border-primary bg-primary/5 ring-2 ring-primary/20' : 'border-outline-variant/30',
    interactive && !selected ? 'hover:border-primary/40 hover:bg-surface-container-lowest' : null,
    className,
  );

  if (as === 'li') {
    return (
      <li className={classes} {...(props as LiHTMLAttributes<HTMLLIElement>)}>
        {children}
      </li>
    );
  }

  return (
    <div className={classes} {...(props as HTMLAttributes<HTMLDivElement>)}>
      {children}
    </div>
  );
}

export interface ProductCardButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  selected?: boolean;
  children: ReactNode;
}

export function ProductCardButton({
  selected = false,
  className,
  children,
  ...props
}: ProductCardButtonProps) {
  return (
    <button
      type="button"
      aria-pressed={selected}
      className={cn(
        CARD_BASE,
        'w-full text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
        selected
          ? 'border-primary bg-primary/5 ring-2 ring-primary/20'
          : 'border-outline-variant/30 hover:border-primary/40 hover:bg-surface-container-lowest',
        className,
      )}
      {...props}
    >
      {children}
    </button>
  );
}
