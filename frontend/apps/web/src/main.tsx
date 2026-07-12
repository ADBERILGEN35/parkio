import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';

function normalizedPathname() {
  return window.location.pathname.replace(/\/+$/, '') || '/';
}

async function bootstrap() {
  const root = createRoot(document.getElementById('root')!);
  const pathname = normalizedPathname();

  if (pathname === '/') {
    const { LandingApp } = await import('./landing/LandingApp');
    root.render(
      <StrictMode>
        <LandingApp />
      </StrictMode>,
    );
    return;
  }

  if (pathname === '/privacy' || pathname === '/terms') {
    await import('./styles/index.css');
    const { PrivacyPage, TermsPage } = await import('./pages/LegalPage');
    const Page = pathname === '/privacy' ? PrivacyPage : TermsPage;
    root.render(
      <StrictMode>
        <BrowserRouter>
          <Page />
        </BrowserRouter>
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
