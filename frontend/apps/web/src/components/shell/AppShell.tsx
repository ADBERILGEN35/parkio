import { Outlet } from 'react-router-dom';
import { DesktopNav } from './DesktopNav';
import { MobileNav } from './MobileNav';

/**
 * Persistent authenticated app shell — fixed desktop top nav, fixed mobile bottom
 * nav, and a scrollable (or full-bleed) main canvas via `<Outlet />`.
 * DESIGN_SYSTEM §2.7 app shell.
 */
export function AppShell() {
  return (
    <div className="flex h-dvh flex-col overflow-hidden bg-background text-on-background">
      <DesktopNav />
      {/*
        Desktop fixed header is md+ only. Mobile must NOT reserve pt-16 for it —
        that caused the large blank strip above content pages in production.
        Bottom padding clears the fixed MobileNav (+ safe-area).
      */}
      <main className="flex min-h-0 flex-1 flex-col overflow-y-auto pt-desktop-nav pb-mobile-nav">
        <Outlet />
      </main>
      <MobileNav />
    </div>
  );
}
