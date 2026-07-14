import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Outlet, matchPath, useLocation } from 'react-router-dom';

const ROUTE_TITLE_KEYS: Array<{ pattern: string; titleKey: string }> = [
  { pattern: '/', titleKey: 'titles.home' },
  { pattern: '/login', titleKey: 'titles.login' },
  { pattern: '/register', titleKey: 'titles.register' },
  { pattern: '/forgot-password', titleKey: 'titles.forgotPassword' },
  { pattern: '/reset-password', titleKey: 'titles.resetPassword' },
  { pattern: '/check-email', titleKey: 'titles.checkEmail' },
  { pattern: '/verify-email', titleKey: 'titles.verifyEmail' },
  { pattern: '/terms', titleKey: 'titles.terms' },
  { pattern: '/privacy', titleKey: 'titles.privacy' },
  { pattern: '/preparing', titleKey: 'titles.preparing' },
  { pattern: '/map', titleKey: 'titles.map' },
  { pattern: '/spots/:spotId', titleKey: 'titles.spotDetails' },
  { pattern: '/my-spots', titleKey: 'titles.mySpots' },
  { pattern: '/upload', titleKey: 'titles.upload' },
  { pattern: '/profile', titleKey: 'titles.profile' },
  { pattern: '/reports', titleKey: 'titles.reports' },
  { pattern: '/notifications', titleKey: 'titles.notifications' },
  { pattern: '/gamification', titleKey: 'titles.gamification' },
  { pattern: '/leaderboard', titleKey: 'titles.leaderboard' },
  { pattern: '/moderation', titleKey: 'titles.moderation' },
  { pattern: '/analytics', titleKey: 'titles.analytics' },
];

function titleKeyFor(pathname: string) {
  return (
    ROUTE_TITLE_KEYS.find((route) => matchPath({ path: route.pattern, end: true }, pathname))
      ?.titleKey ?? 'titles.notFound'
  );
}

function focusRouteTarget() {
  const target =
    document.querySelector<HTMLElement>('[data-route-focus]') ??
    document.querySelector<HTMLElement>('h1') ??
    document.querySelector<HTMLElement>('main');
  if (!target) return;

  const hadTabIndex = target.hasAttribute('tabindex');
  const previousTabIndex = target.getAttribute('tabindex');
  if (!hadTabIndex) target.setAttribute('tabindex', '-1');
  target.focus({ preventScroll: true });
  if (!hadTabIndex) {
    target.addEventListener(
      'blur',
      () => {
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
      document.title = t(titleKeyFor(location.pathname));
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
