import { useEffect } from 'react';
import { Outlet, matchPath, useLocation } from 'react-router-dom';

const ROUTE_TITLES: Array<{ pattern: string; title: string }> = [
  { pattern: '/login', title: 'Parkio — Login' },
  { pattern: '/register', title: 'Parkio — Register' },
  { pattern: '/forgot-password', title: 'Parkio — Forgot Password' },
  { pattern: '/reset-password', title: 'Parkio — Reset Password' },
  { pattern: '/check-email', title: 'Parkio — Check Email' },
  { pattern: '/verify-email', title: 'Parkio — Verify Email' },
  { pattern: '/preparing', title: 'Parkio — Preparing Account' },
  { pattern: '/map', title: 'Parkio — Map' },
  { pattern: '/spots/:spotId', title: 'Parkio — Spot Details' },
  { pattern: '/my-spots', title: 'Parkio — My Spots' },
  { pattern: '/upload', title: 'Parkio — Share a Spot' },
  { pattern: '/profile', title: 'Parkio — Settings' },
  { pattern: '/reports', title: 'Parkio — Reports' },
  { pattern: '/notifications', title: 'Parkio — Notifications' },
  { pattern: '/gamification', title: 'Parkio — Progress' },
  { pattern: '/leaderboard', title: 'Parkio — Leaderboard' },
  { pattern: '/moderation', title: 'Parkio — Moderation' },
  { pattern: '/analytics', title: 'Parkio — Analytics' },
];

function titleFor(pathname: string) {
  return ROUTE_TITLES.find((route) => matchPath({ path: route.pattern, end: true }, pathname))
    ?.title ?? 'Parkio';
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

export function RouteAccessibility() {
  const location = useLocation();

  useEffect(() => {
    document.title = titleFor(location.pathname);
    window.requestAnimationFrame(focusRouteTarget);
  }, [location.pathname]);

  return <Outlet />;
}
