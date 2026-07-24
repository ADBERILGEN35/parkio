import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Outlet, useLocation } from 'react-router-dom';
import { getRouteDocumentTitleKey } from '@/routing/route-manifest';

function focusRouteTarget() {
  const target =
    document.querySelector<HTMLElement>('[data-route-focus]') ??
    document.querySelector<HTMLElement>('h1') ??
    document.querySelector<HTMLElement>('main');
  if (!target) return;

  const hadTabIndex = target.hasAttribute('tabindex');
  const previousTabIndex = target.getAttribute('tabindex');
  if (!hadTabIndex) target.setAttribute('tabindex', '-1');
  target.classList.add('parkio-route-focus');
  target.focus({ preventScroll: true });
  if (!hadTabIndex) {
    target.addEventListener(
      'blur',
      () => {
        target.classList.remove('parkio-route-focus');
        if (previousTabIndex === null) target.removeAttribute('tabindex');
        else target.setAttribute('tabindex', previousTabIndex);
      },
      { once: true },
    );
  }
}

/**
 * Sets a locale-aware `document.title` from the current route and keeps it
 * in sync when i18n language changes.
 */
export function RouteAccessibility() {
  const location = useLocation();
  const { t, i18n } = useTranslation('common');

  useEffect(() => {
    const applyTitle = () => {
      document.title = t(getRouteDocumentTitleKey(location.pathname));
    };
    applyTitle();
    i18n.on('languageChanged', applyTitle);
    return () => {
      i18n.off('languageChanged', applyTitle);
    };
  }, [location.pathname, t, i18n]);

  useEffect(() => {
    window.requestAnimationFrame(focusRouteTarget);
  }, [location.pathname]);

  return <Outlet />;
}
