import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

async function bootstrap() {
  const root = createRoot(document.getElementById('root')!);

  await import('./styles/index.css');

  const [
    { App },
    { createWebAppRuntime },
    { initFrontendErrorReporting },
    { registerServiceWorker },
    { initI18n },
  ] =
    await Promise.all([
      import('./App'),
      import('./app/runtime'),
      import('./observability/errorReporting'),
      import('./pwa/registerServiceWorker'),
      import('./i18n'),
    ]);

  await initI18n();
  const runtime = createWebAppRuntime();
  initFrontendErrorReporting(() => runtime.authStore.getState().isAuthenticated);
  registerServiceWorker();

  root.render(
    <StrictMode>
      <App runtime={runtime} />
    </StrictMode>,
  );
}

void bootstrap();
