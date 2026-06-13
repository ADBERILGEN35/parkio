import { Icon } from './Icon';

export interface ErrorMessageProps {
  message: string;
  traceId?: string;
  code?: string;
}

/** Soft error panel: tinted error-container background, solid error accents. */
export function ErrorMessage({ message, traceId, code }: ErrorMessageProps) {
  return (
    <div
      role="alert"
      className="flex gap-sm rounded-xl border border-error/30 bg-error-container/40 p-md text-body-md text-on-error-container"
    >
      <Icon name="error" className="text-[18px] leading-none text-error" />
      <div className="min-w-0">
        <p className="m-0">{message}</p>
        {code ? <p className="m-0 mt-xs text-label-sm opacity-85">Code: {code}</p> : null}
        {traceId ? <p className="m-0 mt-xs text-label-sm opacity-85">Trace: {traceId}</p> : null}
      </div>
    </div>
  );
}
