interface BrandMarkProps {
  compact?: boolean;
}

export function BrandMark({ compact = false }: BrandMarkProps) {
  const dimensions = compact ? { width: 512, height: 512 } : { width: 456, height: 128 };

  return (
    <a className="landing-brand" href="/" aria-label="Parkio home">
      <img
        className={compact ? 'landing-brand__logo landing-brand__logo--compact' : 'landing-brand__logo'}
        src={compact ? '/icons/parkio-icon.svg' : '/logo.svg'}
        alt=""
        aria-hidden="true"
        {...dimensions}
      />
    </a>
  );
}
