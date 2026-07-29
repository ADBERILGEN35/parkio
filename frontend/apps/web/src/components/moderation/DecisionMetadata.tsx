export type DecisionMetadataItem = {
  label: string;
  value: string;
  mono?: boolean;
};

/** Compact key/value grid for decision metadata (source, code, date, policy). */
export function DecisionMetadata({ items }: { items: DecisionMetadataItem[] }) {
  const visible = items.filter((item) => item.value.trim().length > 0);
  if (visible.length === 0) {
    return null;
  }

  return (
    <dl
      className="m-0 grid grid-cols-1 gap-sm sm:grid-cols-2"
      data-testid="decision-metadata"
    >
      {visible.map((item) => (
        <div key={item.label}>
          <dt className="m-0 text-label-sm text-on-surface-variant">{item.label}</dt>
          <dd
            className={`m-0 mt-xs break-all text-body-md text-on-surface ${item.mono ? 'font-mono text-label-sm' : ''}`}
          >
            {item.value}
          </dd>
        </div>
      ))}
    </dl>
  );
}
