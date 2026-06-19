import { toast } from 'sonner';

type ToastKind = 'success' | 'error' | 'warning' | 'info';

function toastId(kind: ToastKind, message: string) {
  return `${kind}:${message}`;
}

export function showSuccess(message: string) {
  toast.success(message, { id: toastId('success', message) });
}

export function showError(message: string) {
  toast.error(message, { id: toastId('error', message) });
}

export function showWarning(message: string) {
  toast.warning(message, { id: toastId('warning', message) });
}

export function showInfo(message: string) {
  toast.info(message, { id: toastId('info', message) });
}
