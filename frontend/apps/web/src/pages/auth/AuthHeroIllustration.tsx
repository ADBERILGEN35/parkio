/**
 * Decorative hero for the auth split layout: a top-down street grid with a row of parking bays,
 * a dotted route and a pin on the one free bay.
 *
 * This is an original vector drawn for Parkio in this repository, with no third-party artwork
 * (owner decision B10f: project-owned visuals with verified provenance). It replaces the
 * images.unsplash.com photo, so the auth pages make no third-party image request (CL-F39.2).
 *
 * Shapes use `currentColor` at low opacity over the pane's brand gradient, and the pin's "P"
 * uses the primary token, so the drawing follows the theme. It is aria-hidden and needs no fonts.
 */
export function AuthHeroIllustration({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 800 1000"
      preserveAspectRatio="xMidYMid slice"
      aria-hidden="true"
      focusable="false"
      className={className}
    >
      <g fill="currentColor" fillOpacity={0.08}>
        <rect x="40" y="40" width="200" height="170" rx="20" />
        <rect x="320" y="40" width="200" height="170" rx="20" />
        <rect x="600" y="40" width="180" height="170" rx="20" />
        <rect x="40" y="290" width="200" height="230" rx="20" />
        <rect x="320" y="290" width="200" height="190" rx="20" />
        <rect x="600" y="290" width="180" height="230" rx="20" />
        <rect x="40" y="600" width="200" height="150" rx="20" />
        <rect x="600" y="600" width="180" height="110" rx="20" />
      </g>

      <g stroke="currentColor" strokeOpacity={0.16} strokeWidth={32} strokeLinecap="round" fill="none">
        <path d="M-40 250 H840" />
        <path d="M-40 560 H840" />
        <path d="M280 -40 V1040" />
        <path d="M560 -40 V1040" />
        <path d="M-40 940 L840 760" />
      </g>

      <g stroke="currentColor" strokeOpacity={0.3} strokeWidth={3} strokeDasharray="16 20" fill="none">
        <path d="M-40 250 H840" />
        <path d="M-40 560 H840" />
        <path d="M280 -40 V1040" />
        <path d="M560 -40 V1040" />
        <path d="M-40 940 L840 760" />
      </g>

      <g stroke="currentColor" strokeOpacity={0.35} strokeWidth={3} strokeLinecap="round">
        <path d="M606 488 V522" />
        <path d="M640 488 V522" />
        <path d="M674 488 V522" />
        <path d="M708 488 V522" />
        <path d="M742 488 V522" />
        <path d="M776 488 V522" />
      </g>

      <g fill="currentColor" fillOpacity={0.26}>
        <rect x="611" y="491" width="24" height="28" rx="6" />
        <rect x="645" y="491" width="24" height="28" rx="6" />
        <rect x="713" y="491" width="24" height="28" rx="6" />
        <rect x="747" y="491" width="24" height="28" rx="6" />
      </g>

      <path
        d="M90 1010 C 130 880 250 840 280 730 S 330 600 420 580 S 640 576 691 530"
        stroke="currentColor"
        strokeOpacity={0.8}
        strokeWidth={6}
        strokeLinecap="round"
        strokeDasharray="1 16"
        fill="none"
      />

      <g transform="translate(691 518)">
        <ellipse cx="0" cy="4" rx="34" ry="10" fill="currentColor" fillOpacity={0.18} />
        <circle cx="0" cy="-74" r="62" fill="currentColor" fillOpacity={0.12} />
        <path
          d="M0 0 C -26 -30 -40 -48 -40 -74 A 40 40 0 1 1 40 -74 C 40 -48 26 -30 0 0 Z"
          fill="currentColor"
          fillOpacity={0.96}
        />
        <path
          d="M-9 -56 V-94 H3 A 12 12 0 0 1 3 -70 H-9"
          className="text-primary"
          stroke="currentColor"
          strokeWidth={7}
          strokeLinecap="round"
          strokeLinejoin="round"
          fill="none"
        />
      </g>
    </svg>
  );
}
