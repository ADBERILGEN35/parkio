import type { ReactNode } from 'react';

export interface PageShellProps {
  title: string;
  children: ReactNode;
}

/**
 * Lightweight page content container: centered max-width column + page header.
 * Renders a `<section>` (not `<main>`) because the app-level `<main>` landmark
 * and scroll area are owned by AppShell; nesting a second `<main>` here would
 * duplicate the landmark.
 */
export function PageShell({ title, children }: PageShellProps) {
  return (
    <section className="bg-background px-md py-lg text-on-background md:px-xl">
      <div className="mx-auto w-full max-w-7xl">
        <header className="mb-lg">
          <h1 className="m-0 text-headline-lg-mobile text-on-surface md:text-headline-lg">
            {title}
          </h1>
        </header>
        {children}
      </div>
    </section>
  );
}
