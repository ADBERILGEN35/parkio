import { useEffect } from 'react';
import { LandingPage } from '@/pages/LandingPage';
import '@/styles/landing.css';

function focusLandingRouteTarget() {
  const target = document.querySelector<HTMLElement>('[data-route-focus]');
  if (!target) return;

  if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1');
  target.focus({ preventScroll: true });
}

export function LandingApp() {
  useEffect(() => {
    document.title = 'Parkio - Community-Powered Parking Intelligence';
    window.requestAnimationFrame(focusLandingRouteTarget);
  }, []);

  return <LandingPage />;
}
