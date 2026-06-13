import { useState, type ReactNode } from 'react';
import { hasPrivilegedRole, isUnreadNotification } from '@parkio/types';
import { Icon, cn } from '@parkio/ui';
import { useQuery } from '@tanstack/react-query';
import { Link, NavLink } from 'react-router-dom';
import { notificationsApi } from '@/api';
import { useAuthStore } from '@/auth/store';

/**
 * Shared top navigation bar (design-system baseline). Routes and link names
 * are unchanged; the visual shell follows DESIGN_SYSTEM.md §2.1 — glass bar,
 * pill links with an active state. On small screens the links collapse behind a
 * hamburger toggle so the bar never wraps into a dense multi-row block.
 */
export function AppNav() {
  const roles = useAuthStore((s) => s.roles);
  const [open, setOpen] = useState(false);
  const privileged = hasPrivilegedRole(roles);
  const close = () => setOpen(false);

  return (
    <nav className="mb-lg rounded-2xl border border-outline-variant/20 bg-surface-container-lowest/80 px-md py-sm shadow-soft backdrop-blur-xl">
      <div className="flex items-center gap-xs">
        <Link
          to="/map"
          className="mr-sm flex items-center gap-xs text-title-lg font-bold text-primary no-underline"
          aria-label="Parkio home"
        >
          <span aria-hidden className="material-symbols-outlined filled select-none">
            local_parking
          </span>
          Parkio
        </Link>

        {/* Desktop links — inline pills (md and up) */}
        <div className="hidden flex-1 items-center gap-xs md:flex">
          <NavItem to="/map">Map</NavItem>
          <NavItem to="/upload">Share a spot</NavItem>
          <NavItem to="/my-spots">My spots</NavItem>
          <NavItem to="/reports">My reports</NavItem>
          <NavItem to="/gamification">Progress</NavItem>
          <NavItem to="/leaderboard">Leaderboard</NavItem>
          {privileged ? (
            <>
              <NavItem to="/moderation">Moderation</NavItem>
              <NavItem to="/analytics">Analytics</NavItem>
            </>
          ) : null}

          <div className="ml-auto flex items-center gap-xs">
            <NavItem to="/notifications">
              Notifications
              <UnreadBadge />
            </NavItem>
            <NavItem to="/profile">Profile</NavItem>
          </div>
        </div>

        {/* Mobile toggle — collapses the links below md */}
        <button
          type="button"
          onClick={() => setOpen((value) => !value)}
          aria-expanded={open}
          aria-controls="app-nav-menu"
          aria-label={open ? 'Close menu' : 'Open menu'}
          className="ml-auto inline-flex items-center gap-xs rounded-full px-sm py-sm text-on-surface-variant transition-colors duration-std hover:bg-surface-container hover:text-on-surface focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary md:hidden"
        >
          <Icon name={open ? 'close' : 'menu'} className="text-[22px] leading-none" />
          {!open ? <UnreadBadge /> : null}
        </button>
      </div>

      {/* Mobile menu — stacked links (below md) */}
      {open ? (
        <div id="app-nav-menu" className="mt-sm flex flex-col gap-xs md:hidden">
          <NavItem to="/map" onNavigate={close}>
            Map
          </NavItem>
          <NavItem to="/upload" onNavigate={close}>
            Share a spot
          </NavItem>
          <NavItem to="/my-spots" onNavigate={close}>
            My spots
          </NavItem>
          <NavItem to="/reports" onNavigate={close}>
            My reports
          </NavItem>
          <NavItem to="/gamification" onNavigate={close}>
            Progress
          </NavItem>
          <NavItem to="/leaderboard" onNavigate={close}>
            Leaderboard
          </NavItem>
          {privileged ? (
            <>
              <NavItem to="/moderation" onNavigate={close}>
                Moderation
              </NavItem>
              <NavItem to="/analytics" onNavigate={close}>
                Analytics
              </NavItem>
            </>
          ) : null}
          <NavItem to="/notifications" onNavigate={close}>
            Notifications
            <UnreadBadge />
          </NavItem>
          <NavItem to="/profile" onNavigate={close}>
            Profile
          </NavItem>
        </div>
      ) : null}
    </nav>
  );
}

function NavItem({
  to,
  children,
  onNavigate,
}: {
  to: string;
  children: ReactNode;
  onNavigate?: () => void;
}) {
  return (
    <NavLink
      to={to}
      onClick={onNavigate}
      className={({ isActive }) =>
        cn(
          'inline-flex items-center rounded-full px-md py-sm text-label-md no-underline transition-colors duration-std',
          isActive
            ? 'bg-primary-container/15 font-bold text-primary'
            : 'text-on-surface-variant hover:bg-surface-container hover:text-on-surface',
        )
      }
    >
      {children}
    </NavLink>
  );
}

/**
 * Unread count derived from the cached `['notifications']` list — the backend has
 * no dedicated unread-count endpoint, so a separate key would double-fetch. No
 * polling: the query refetches only when a page mounts the nav (navigation/reload)
 * or the list is invalidated (mark-as-read).
 */
function UnreadBadge() {
  const unreadCount = useQuery({
    queryKey: ['notifications'],
    queryFn: notificationsApi.getMyNotifications,
    select: (notifications) => notifications.filter(isUnreadNotification).length,
  });

  if (!unreadCount.isSuccess || unreadCount.data === 0) return null;

  return (
    <span className="ml-xs rounded-full bg-primary px-1.5 py-px text-label-sm text-on-primary">
      {unreadCount.data}
    </span>
  );
}
