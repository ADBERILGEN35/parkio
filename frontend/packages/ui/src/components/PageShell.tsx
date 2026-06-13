import type { ReactNode } from 'react';

export interface PageShellProps {
  title: string;
  children: ReactNode;
}

/** Page canvas: design-system background, centered max-width column, page header. */
export function PageShell({ title, children }: PageShellProps) {
  return (
    <main className="min-h-screen bg-background px-md py-lg text-on-background md:px-xl">
      <div className="mx-auto w-full max-w-7xl">
        <header className="mb-lg">
          <h1 className="m-0 text-headline-lg-mobile text-on-surface md:text-headline-lg">
            {title}
          </h1>
        </header>
        {children}
      </div>
    </main>
  );
}
