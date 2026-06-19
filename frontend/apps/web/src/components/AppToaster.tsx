import { Toaster } from 'sonner';

export function AppToaster() {
  return (
    <Toaster
      richColors
      closeButton
      position="top-right"
      toastOptions={{
        duration: 4500,
        className: 'font-sans',
      }}
    />
  );
}
