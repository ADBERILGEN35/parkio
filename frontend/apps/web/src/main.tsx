import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { initFrontendErrorReporting } from './observability/errorReporting';
import { registerServiceWorker } from './pwa/registerServiceWorker';
import './styles/index.css';

initFrontendErrorReporting();
registerServiceWorker();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
