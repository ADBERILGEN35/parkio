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
      <main className="flex min-h-0 flex-1 flex-col overflow-y-auto pt-16 pb-16 md:pb-0">
        <Outlet />
      </main>
      <MobileNav />
    </div>
  );
}
