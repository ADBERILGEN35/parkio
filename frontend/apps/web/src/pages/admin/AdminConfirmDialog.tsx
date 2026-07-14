import { useState, type ReactNode } from 'react';
import { Button, Input } from '@parkio/ui';

interface AdminConfirmDialogProps {
  open: boolean;
  title: string;
  description: ReactNode;
  confirmLabel: string;
  reasonRequired?: boolean;
  busy?: boolean;
  onCancel: () => void;
  onConfirm: (reason: string) => void;
}

/** Modal confirmation for high-impact admin actions; reason is required by default. */
export function AdminConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  reasonRequired = true,
  busy = false,
  onCancel,
  onConfirm,
}: AdminConfirmDialogProps) {
  const [reason, setReason] = useState('');
  if (!open) return null;

  const canSubmit = !busy && (!reasonRequired || reason.trim().length >= 3);

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/40 p-md sm:items-center"
      role="dialog"
      aria-modal="true"
      aria-labelledby="admin-confirm-title"
    >
      <div className="w-full max-w-md rounded-lg bg-surface p-lg shadow-elevation-3">
        <h2 id="admin-confirm-title" className="m-0 text-headline-sm text-on-surface">
          {title}
        </h2>
        <div className="mt-sm text-body-md text-on-surface-variant">{description}</div>
        {reasonRequired ? (
          <label className="mt-md block">
            <span className="mb-xs block text-label-md font-semibold text-on-surface">Reason</span>
            <Input
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="Required for the audit trail"
              disabled={busy}
            />
          </label>
        ) : null}
        <div className="mt-lg flex justify-end gap-sm">
          <Button type="button" variant="ghost" disabled={busy} onClick={onCancel}>
            Cancel
          </Button>
          <Button
            type="button"
            variant="primary"
            disabled={!canSubmit}
            onClick={() => onConfirm(reason.trim())}
          >
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
