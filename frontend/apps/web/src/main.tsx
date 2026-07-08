import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

async function bootstrap() {
  const root = createRoot(document.getElementById('root')!);

  if (window.location.pathname === '/') {
    const { LandingApp } = await import('./landing/LandingApp');
    root.render(
      <StrictMode>
        <LandingApp />
      </StrictMode>,
    );
    return;
  }

  await import('./styles/index.css');

  const [{ App }, { initFrontendErrorReporting }, { registerServiceWorker }] = await Promise.all([
    import('./App'),
    import('./observability/errorReporting'),
    import('./pwa/registerServiceWorker'),
  ]);

  initFrontendErrorReporting();
  registerServiceWorker();

  root.render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

void bootstrap();
