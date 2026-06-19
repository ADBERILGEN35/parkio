import { Icon } from '@parkio/ui';
import { useState, type ReactNode } from 'react';

/**
 * Stitch auth split (`DESIGN_SYSTEM.md` §2.7 "Auth split"): a centered elevated
 * card with a 50/50 photo/form split on desktop. The photo pane carries the
 * brand logo (top-left), a gradient scrim and a glass info card with community
 * social proof (bottom-left). On mobile the hero collapses to a short top banner
 * and the form becomes the primary focus. Ambient blurred blobs sit behind the
 * card per the spec's decoration vocabulary.
 */
export function AuthSplitLayout({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle: string;
  children: ReactNode;
}) {
  return (
    <main className="relative flex min-h-screen items-center justify-center overflow-hidden bg-background px-md py-xl text-on-background">
      {/* Ambient decoration */}
      <div
        aria-hidden
        className="pointer-events-none absolute -left-32 -top-32 h-96 w-96 rounded-full bg-primary/5 blur-3xl"
      />
      <div
        aria-hidden
        className="pointer-events-none absolute -bottom-40 -right-24 h-[28rem] w-[28rem] rounded-full bg-secondary/5 blur-3xl"
      />

      <div className="relative grid w-full max-w-[1100px] overflow-hidden rounded-2xl bg-surface-container-lowest shadow-deep md:min-h-[640px] md:grid-cols-2">
        <AuthHero />

        {/* Form pane */}
        <section className="flex items-center justify-center p-lg sm:p-xl">
          <div className="w-full max-w-md">
            <header className="mb-lg">
              <h1 className="m-0 text-headline-lg-mobile text-on-surface md:text-headline-lg">
                {title}
              </h1>
              <p className="m-0 mt-xs text-body-md text-on-surface-variant">{subtitle}</p>
            </header>
            {children}
          </div>
        </section>
      </div>
    </main>
  );
}

const HERO_IMAGE_URL =
  'https://images.unsplash.com/photo-1506521781263-d8422e82f27a?auto=format&fit=crop&w=1100&q=60';

/** Full-height photo panel with brand lockup + glass social-proof card. */
function AuthHero() {
  const [imageOk, setImageOk] = useState(true);

  return (
    <aside className="relative h-44 overflow-hidden bg-gradient-to-br from-primary to-secondary md:h-auto">
      {imageOk ? (
        <img
          src={HERO_IMAGE_URL}
          alt=""
          aria-hidden
          loading="lazy"
          referrerPolicy="no-referrer"
          onError={() => setImageOk(false)}
          className="absolute inset-0 h-full w-full object-cover"
        />
      ) : null}

      {/* Gradient scrim for legibility + brand tint */}
      <div
        aria-hidden
        className="absolute inset-0 bg-gradient-to-t from-inverse-surface/95 via-inverse-surface/40 to-primary/20"
      />

      {/* Brand lockup — centered on the mobile banner, top-left on desktop */}
      <div className="absolute left-1/2 top-1/2 flex -translate-x-1/2 -translate-y-1/2 items-center gap-xs text-inverse-on-surface md:left-lg md:top-lg md:translate-x-0 md:translate-y-0">
        <Icon name="local_parking" filled className="text-[28px] leading-none" />
        <span className="text-headline-md font-bold">Parkio</span>
      </div>

      {/* Glass social-proof card (desktop only — keeps the mobile banner light) */}
      <div className="absolute inset-x-lg bottom-lg hidden md:block">
        <div className="glass-panel rounded-3xl p-lg shadow-deep">
          <p className="m-0 text-title-lg font-semibold text-on-surface">Concierge for the curb.</p>
          <p className="m-0 mt-xs text-body-md text-on-surface-variant">
            Find and share real parking spots, verified by neighbors in real time.
          </p>
          <div className="mt-md flex items-center gap-sm">
            <AvatarStack />
            <div>
              <div className="flex items-center gap-[1px] text-tertiary" aria-hidden>
                {Array.from({ length: 5 }).map((_, i) => (
                  <Icon key={i} name="star" filled className="text-[14px] leading-none" />
                ))}
              </div>
              <p className="m-0 mt-xs text-label-sm text-on-surface-variant">
                Trusted by drivers across the city
              </p>
            </div>
          </div>
        </div>
      </div>
    </aside>
  );
}

/** Decorative overlapping avatar discs (brand chrome, not backed by real users). */
function AvatarStack() {
  const tones = [
    'bg-primary-container text-on-primary-container',
    'bg-secondary-container text-on-secondary-container',
    'bg-tertiary-container text-on-tertiary-container',
  ];
  return (
    <div className="flex -space-x-2" aria-hidden>
      {tones.map((tone, i) => (
        <span
          key={i}
          className={`flex h-8 w-8 items-center justify-center rounded-full border-2 border-surface-container-lowest text-label-sm font-semibold ${tone}`}
        >
          <Icon name="person" filled className="text-[16px] leading-none" />
        </span>
      ))}
    </div>
  );
}
