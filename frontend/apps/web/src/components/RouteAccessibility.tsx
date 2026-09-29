import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Outlet, useLocation } from 'react-router-dom';
import { getRouteDocumentTitleKey } from '@/routing/route-manifest';
import {
  noteAnalyticsInteraction,
  setAnalyticsFocused,
  setAnalyticsForeground,
  trackScreenViewed,
} from '@/services/productAnalytics';

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
 * in sync when i18n language changes. Also drives Y04 screen_viewed + active-time.
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

  useEffect(() => {
    // Pathname only — never search/hash (query canaries).
    trackScreenViewed(location.pathname);
  }, [location.pathname]);

  useEffect(() => {
    const onVisibility = () => {
      const visible = document.visibilityState === 'visible';
      setAnalyticsForeground(visible);
      if (visible) noteAnalyticsInteraction();
    };
    const onFocus = () => {
      setAnalyticsFocused(true);
      noteAnalyticsInteraction();
    };
    const onBlur = () => setAnalyticsFocused(false);
    const onInteraction = () => noteAnalyticsInteraction();

    setAnalyticsForeground(document.visibilityState === 'visible');
    setAnalyticsFocused(document.hasFocus());

    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('focus', onFocus);
    window.addEventListener('blur', onBlur);
    window.addEventListener('pointerdown', onInteraction, { passive: true });
    window.addEventListener('keydown', onInteraction);

    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('focus', onFocus);
      window.removeEventListener('blur', onBlur);
      window.removeEventListener('pointerdown', onInteraction);
      window.removeEventListener('keydown', onInteraction);
    };
  }, []);

  return <Outlet />;
}
