# Parkio Design System

> Extracted from the Stitch HTML kit (`stitch_parkio_ui_ux_design_system.zip`, 14 screens + 2 Stitch `DESIGN.md` specs + 1 loading shader).
> This document records **what exists in the mockups** — it does not redesign anything.

**Brand voice (from the source specs):** "Concierge for the curb." Sophisticated Minimalism / Tactile Modern. Spatial clarity over structural lines: borders are replaced by soft tonal surfaces and ambient shadows; glassmorphism for anything floating over the map.

**⚠ Two token generations exist in the kit:**

| | "Parkio" (v1) | "Parkio V2" (canonical) |
|---|---|---|
| Used by | `login_desktop`, `register_desktop` | All 12 other screens |
| Secondary | Indigo `#4b41e1` / container `#645efb` | Emerald `#006c49` / container `#6cf8bb` |
| Tertiary | Burnt orange `#a33200` / `#cc4204` | Amber `#7f4f00` / container `#a06500` |
| Type scale | `display, h1, h1-mobile, h2, h3, h4, body-lg, body-md, label-caps, status-label` (px line-heights) | `display-lg, headline-lg, headline-lg-mobile, headline-md, title-lg, body-lg, body-md, label-md, label-sm` (unitless line-heights) |
| Extra spacing | `2xl: 48px`, `margin-mobile: 16px`, `margin-desktop: 32px` | `container-margin: 20px` |

Everything else (primary, surfaces, error, outline, radii, base spacing) is identical. **V2 is treated as canonical below**; v1 deltas are noted where they matter.

---

## 1. Design Tokens

### 1.1 Color tokens (Material-3-style roles, light theme only — all screens use `html.light`, `darkMode: "class"` is configured and `dark:` variants appear in a few V2 screens)

#### Primary (Electric / Trustworthy Blue)

| Token | Value | Observed use |
|---|---|---|
| `primary` | `#0050cb` | Primary buttons, active nav, links, focus rings, chart bars, heatmap cells, progress fills, active map marker |
| `on-primary` | `#ffffff` | Text/icons on primary |
| `primary-container` | `#0066ff` | Button hover target, active sidebar item bg (settings), icon discs |
| `on-primary-container` | `#f8f7ff` | Text on primary-container |
| `primary-fixed` | `#dae1ff` | Selected selection-card bg, gradient endpoints |
| `primary-fixed-dim` | `#b3c5ff` | Gradient endpoint (XP bar), 1st-place card border |
| `on-primary-fixed` | `#001849` | Text on primary-fixed |
| `on-primary-fixed-variant` | `#003fa4` | Button `active:` state (login), button hover (wizard) |
| `inverse-primary` | `#b3c5ff` | Brand text on dark surfaces / `dark:` variants |
| `surface-tint` | `#0054d6` | Button hover bg (map screen), tinted icon discs |

#### Secondary (Success / Verified — Emerald in V2)

| Token | V2 value | v1 value | Observed use |
|---|---|---|---|
| `secondary` | `#006c49` | `#4b41e1` | "Verified" badges/icons, positive trends (+$24.00, trending_up), enabled-state text, EV icons |
| `on-secondary` | `#ffffff` | `#ffffff` | Timeline check disc text |
| `secondary-container` | `#6cf8bb` | `#645efb` | Success icon discs (Level Up, wizard success), "Primary" vehicle chip |
| `on-secondary-container` | `#00714d` | `#fffbff` | Text on the above |
| `secondary-fixed` | `#6ffbbe` | `#e2dfff` | Decorative gradient endpoint (landing hero) |
| `secondary-fixed-dim` | `#4edea3` | `#c3c0ff` | — |
| `on-secondary-fixed` | `#002113` | `#0f0069` | — |
| `on-secondary-fixed-variant` | `#005236` | `#3323cc` | — |

#### Tertiary (Warning / Streaks — Amber in V2)

| Token | V2 value | v1 value | Observed use |
|---|---|---|---|
| `tertiary` | `#7f4f00` | `#a33200` | "In Review" status text, star/streak icons, 2hr-max chip icon |
| `on-tertiary` | `#ffffff` | `#ffffff` | — |
| `tertiary-container` | `#a06500` | `#cc4204` | "Top 1%" badge bg, flame icon tint, +50 Points icon |
| `on-tertiary-container` | `#fff7f1` | `#fff6f4` | Text on tertiary-container, avatar fallback |
| `tertiary-fixed` | `#ffddb8` | `#ffdbd0` | — |
| `tertiary-fixed-dim` | `#ffb95f` | `#ffb59d` | — |
| `on-tertiary-fixed` | `#2a1700` | `#390c00` | — |
| `on-tertiary-fixed-variant` | `#653e00` | `#832600` | — |

#### Error / Danger (identical in both generations)

| Token | Value | Observed use |
|---|---|---|
| `error` | `#ba1a1a` | "Urgent" badges, destructive buttons (Suspend Listing), negative amounts, notification dot, rank-down arrow |
| `on-error` | `#ffffff` | Text on error |
| `error-container` | `#ffdad6` | Urgent count chip bg, cancellation icon disc (`/50`), sign-out button (`/50`) |
| `on-error-container` | `#93000a` | Text on error-container |

#### Surfaces & neutrals (identical in both generations)

| Token | Value | Observed use |
|---|---|---|
| `background` / `surface` / `surface-bright` | `#f8f9ff` | Page background, nav bars (at `/70`–`/90` opacity), inputs |
| `surface-dim` | `#cbdbf5` | — (defined, unused in screens) |
| `surface-container-lowest` | `#ffffff` | Cards, modals, white panels |
| `surface-container-low` | `#eff4ff` | Sidebar bg, quote boxes, list-row bg, card inner borders |
| `surface-container` | `#e5eeff` | Hover bg, search pills, icon-button bg, heatmap "empty" cell |
| `surface-container-high` | `#dce9ff` | Active sidebar item bg, progress-bar tracks, dividers, chips |
| `surface-container-highest` / `surface-variant` | `#d3e4fe` | Card borders, avatar fallback bg, toggle track, ring track |
| `on-surface` / `on-background` | `#0b1c30` | Primary text |
| `on-surface-variant` | `#424656` | Secondary text, inactive nav, labels |
| `outline` | `#727687` | Tertiary/placeholder text, timestamps, locked-achievement tint, scrollbar hover |
| `outline-variant` | `#c2c6d8` | Borders (often at `/10`–`/50` opacity), dividers, scrollbar thumb, dashed dropzone, drag handle |
| `inverse-surface` | `#213145` | Dark footer (landing), photo gradient overlay, chart tooltip bg |
| `inverse-on-surface` | `#eaf1ff` | Text on inverse-surface |

#### Status color narrative (from the Stitch specs — semantic intent)

The written specs name a slate/emerald/amber/rose status system; the *implemented* screens map those intents onto the M3 roles above:

| Status | Spec color | Implemented as |
|---|---|---|
| Active / current session | `#0066FF` | `primary` / `primary-container` |
| Verified / success | `#059669` / `#10B981` | `secondary` (text+`/10` bg badge) |
| Suspicious / warning / in-review | `#D97706` / `#F59E0B` | `tertiary` (text) + `tertiary-container/20` (bg) |
| Filled / inactive / pending | `#94A3B8` | `on-surface-variant` text + `/10` bg, or `surface-variant` bg |
| Expired / urgent / rejected | `#EF4444` / `#991B1B` | `error` (text+`/10` bg) or solid `error` |

#### Hard-coded one-off colors (exist in the HTML, not tokenized)

- **Podium medals:** gold `#FFD700`, silver `#C0C0C0`, bronze `#CD7F32` (leaderboard trophies)
- **Streak / fire palette** (contribution hub): `#ea580c` (flame, day discs), gradient `#fff7ed → #ffedd5`, border `#fed7aa`, text `#9a3412` / `#c2410c`, future-day `#fdba74` — Tailwind orange-* family
- **Celebration banner gradient:** `#1e3a8a → #3b82f6`, with `text-blue-100`/`blue-50` and `text-yellow-300` trophy
- **Gradient text** (landing hero): `linear-gradient(135deg, #0050cb, #006c49)`
- **Pulse glow:** `rgba(0, 80, 203, 0.4)` (primary at 40%)
- **Shader skeleton base:** `vec3(0.973, 0.976, 1.0)` = `#f8f9ff` (surface)

### 1.2 Typography scale

Font family: **Inter** (weights loaded: 400, 500, 600, 700). Icons: **Material Symbols Outlined** (variable font; `FILL 0` default, `FILL 1` for active/filled states, `wght 400, GRAD 0, opsz 24`).

**V2 scale (canonical):**

| Token | Size | Line height | Weight | Letter spacing | Observed use |
|---|---|---|---|---|---|
| `display-lg` | 48px | 1.1 | 700 | −0.02em | Hero headlines, KPI numbers, trust score, profile name |
| `headline-lg` | 32px | 1.2 | 700 | −0.02em | Page titles (desktop), streak headline |
| `headline-lg-mobile` | 24px | 1.2 | 700 | — | Page titles (mobile) |
| `headline-md` | 24px | 1.3 | 600 | −0.01em | Brand wordmark, prices, section stats, card metrics |
| `title-lg` | 20px | 1.4 | 600 | — | Card titles, section headings, top-nav links, tab labels |
| `body-lg` | 16px | 1.6 | 400 | — | Long-form copy, report statements |
| `body-md` | 14px | 1.5 | 400 | — | Default body (set on `<body>`), descriptions |
| `label-md` | 12px | 1 | 600 | 0.01em | Buttons, badges, nav items, field labels, metadata |
| `label-sm` | 11px | 1 | 500 | — | Timestamps, micro-captions, uppercase KPI labels, bottom-nav labels |

**v1 scale (login/register only):** `display` 36/44 700 −0.02em · `h1` 30/38 700 −0.01em · `h1-mobile` 24/32 700 · `h2` 24/32 600 · `h3` 20/28 600 · `h4` 18/26 600 · `body-lg` 16/24 400 · `body-md` 14/20 400 · `label-caps` 12/16 700 +0.05em (uppercase field labels) · `status-label` 13/18 600 (buttons, field labels).

Conventions: headlines use bold weights + negative tracking; uppercase labels add `uppercase tracking-wider`/`tracking-widest`; usage pattern in markup is always the pair `font-{token} text-{token}`.

### 1.3 Border radius scale

Configured in every screen's Tailwind config (overriding defaults):

| Token | Value | Observed use |
|---|---|---|
| `rounded` (DEFAULT) | 0.25rem (4px) | Plate-number chip, evidence photo labels, micro trend chips |
| `rounded-lg` | 0.5rem (8px) | Inputs, standard buttons, nav items, thumbnails, quote boxes |
| `rounded-xl` | 0.75rem (12px) | Cards (KPI, notification, vehicle, achievement), panes, dropzone |
| `rounded-2xl` | 1rem (16px) — Tailwind default, used heavily | Settings section cards, attribute chips, hero CTAs, evidence frames |
| `rounded-3xl` | 1.5rem (24px) — Tailwind default | Bento cards, contributor card, detail sidebar (`rounded-t-3xl` / `rounded-l-3xl` sheets) |
| `rounded-[1.5rem]` / `rounded-[2rem]` | 24px / 32px arbitrary | Spot cards (map sidebar), glass sidebar left edge |
| `rounded-full` | 9999px | Pills everywhere: buttons, badges, chips, markers, search bars, avatars, toggles, progress bars |

The Stitch spec narrative: 8px standard UI, 16px large containers, full-pill for badges/chips/markers — the implementation matches, plus the 24–32px tier for premium cards/sheets.

### 1.4 Spacing scale

4px baseline. Named tokens are used as Tailwind spacing (`p-md`, `gap-lg`, `px-container-margin`, …):

| Token | Value | Notes |
|---|---|---|
| `base` / `xs` | 4px | Inline micro-gaps, chip padding-y |
| `sm` | 8px | Sibling element gaps, chip padding |
| `md` | 16px | Card padding, list gaps, default gutter (`gutter: 16px`) |
| `lg` | 24px | Section padding, sidebar padding |
| `xl` | 32px | Section separation, page padding |
| `2xl` | 48px | v1 only |
| `container-margin` | 20px | V2 mobile safe-area margin (bottom-nav px, mobile page px) |
| `margin-mobile` / `margin-desktop` | 16px / 32px | v1 only (auth pages) |

Raw Tailwind steps (`p-5`, `px-6`, `py-3`, `gap-2`, etc.) also appear alongside the named tokens. Layout rhythm per the spec: 8–12px within a group, 32px+ between sections; desktop content max-widths observed: `max-w-7xl` (dashboard/nav), `max-w-5xl` (hub, wizard), `max-w-4xl` (leaderboard list, header pill), `max-w-3xl` (notifications), `max-w-md` (forms).

### 1.5 Shadow / elevation scale

| Level | Value | Name in HTML | Observed use |
|---|---|---|---|
| 0 | none | — | Page base `#f8f9ff` |
| 1 — Ambient soft | `0px 4px 20px rgba(0,0,0,0.05)` (one screen uses `0.03`) | `.soft-shadow`, `.ambient-shadow(-sm)`, `shadow-[0px_4px_20px_rgba(0,0,0,0.05)]` | Cards, inputs (V2 borderless inputs), filter bar, glass-card |
| 2 — Ambient deep | `0px 12px 40px rgba(0,0,0,0.1)` (one screen `0.08`) | `.deep-shadow`, `.ambient-shadow-md/-deep` | Modals/detail panes, hero mockup, podium avatars (`0.15` for 1st place) |
| Legacy (v1) | `0 4px 12px rgba(0,0,0,0.08)` | register card | Level-2 of the v1 spec (which also names `0 10px 25px rgba(0,0,0,0.12)` for overlays) |
| Directional | `-10px 0px 40px rgba(0,0,0,0.08)` (left, detail sidebar) · `0px -10px 40px rgba(0,0,0,0.05)` (up, sticky action footer) · `0px -4px 20px rgba(0,0,0,0.05)` (up, bottom nav) | arbitrary | Sheets/sticky bars |
| Colored glow | `0px 8px 30px rgba(59,130,246,0.3)` | celebration banner | Milestone card |
| Tailwind built-ins | `shadow-sm/md/lg/xl/2xl` | everywhere | Buttons (`shadow-sm`→`hover:shadow`), pills (`shadow-md`), floating header (`shadow-xl`), glass sidebar (`shadow-2xl`) |

**Glassmorphism recipe** (nav bars, map overlays, floating panels):
```css
background: rgba(248, 249, 255, 0.7);   /* .glass-panel — variants: 0.85, white 0.8/0.85/0.9/0.95 */
backdrop-filter: blur(20px);             /* variants: blur(16px); Tailwind backdrop-blur-md/xl/2xl */
border: 1px solid rgba(255, 255, 255, 0.3);  /* optional */
/* dark variant: rgba(11, 28, 48, 0.85) */
```

### 1.6 Motion tokens

| Token | Value | Use |
|---|---|---|
| `--ms-fast` | 100ms | Micro feedback |
| `--ms-std` | 250ms | Hover/nav transitions |
| `--ms-fluid` | 400ms | Panel slide-ins |
| Spring ease | `cubic-bezier(0.34, 1.56, 0.64, 1)` | `.hover-lift`, `.slide-in-right` (overshoot) |
| Standard ease | `cubic-bezier(0.4, 0, 0.2, 1)` over 0.4s | Wizard progress bar |
| Press feedback | `active:scale-95` (buttons), `active:scale-90` (bottom-nav tabs), `active:scale-98` (side-nav items) | Everywhere interactive |
| `.hover-lift` | `translateY(-4px)` + `0 12px 24px rgba(0,0,0,0.1)` | Spot cards |
| Image zoom | `group-hover:scale-105`, `duration-500`–`duration-1000` | Card/hero imagery |
| `@keyframes pulse` (pulse-glow) | box-shadow ring 0→10px, `rgba(0,80,203,0.4)`, 2s infinite | Active map markers |
| `@keyframes slideInRight` | translateX(100%)→0, 400ms spring | Map sidebar entrance |
| `@keyframes fadeIn` | opacity+10px rise, 0.3s | Wizard steps |
| `@keyframes float` | ±10px Y, 6s | Landing hero mockup |
| `animate-bounce` | Tailwind default | Map teardrop pin |
| `fillRing` | stroke-dashoffset 283→14.7, 1.5s ease-out | Trust score SVG ring |
| Skeleton shimmer | WebGL fragment shader: surface base `#f8f9ff` + narrow sine highlight band (`pow(shimmer,10) * 0.03`), drifting at `u_time * 3.0` | Global loading state (`shader/code.html`) |

---

## 2. Component Library (inventory of what exists)

### 2.1 Navigation patterns

| Component | Anatomy (as built) |
|---|---|
| **TopNavBar (desktop)** | Fixed, `h-16`, `bg-surface/70 backdrop-blur-xl shadow-sm`, `max-w-7xl` inner. Left: brand wordmark (`headline-md` bold primary) + links. Right: "List a Spot" primary pill, bell icon button, avatar (40px, `hover:ring-2 ring-primary`). Active link: `text-primary font-bold border-b-2 border-primary`. |
| **Floating header pill (map v3)** | Fixed top-center, `max-w-4xl`, `rounded-full bg-surface/90 backdrop-blur-xl shadow-xl border border-surface-container-high/50`, contains brand, links, inline search pill, bell, CTA, avatar. |
| **SideNavBar (desktop app shell)** | Fixed `w-64 h-full bg-surface`(-`container-low`) `shadow-md py-lg`. Header: brand + tagline "Concierge for the curb" (`label-md`). Items: `flex gap-md px-md py-sm rounded-lg`; inactive `text-on-surface-variant hover:bg-surface-container`; **active: `text-primary font-bold border-r-4 border-primary bg-surface-container-high` + FILL-1 icon**. Footer: full-width "List a Spot" pill + user snippet (avatar, name `label-md`, role `label-sm`). |
| **BottomNavBar (mobile)** | Fixed bottom, `h-16 bg-surface/70 backdrop-blur-xl rounded-t-xl shadow-lg px-container-margin pb-safe`. 5 tabs: Explore / Saved / List / Rankings / Profile — icon + `label-sm` text stacked. **Active tab: `bg-primary-container text-on-primary-container rounded-full px-4 py-1` + FILL-1 icon.** `active:scale-90 duration-150`. |
| **In-page settings nav** | Sticky anchor list, items `rounded-xl px-lg py-md label-md`; active `bg-primary-container text-on-primary-container shadow-sm` + filled icon. |
| **Underline tabs** | `px-md py-sm border-b-2`, active `border-primary text-primary`, inactive `border-transparent text-on-surface-variant`. Used for queue filters (All/Reports/Appeals) and content tabs (Activity/Impact/Achievements — `border-b-[3px]`, `title-lg`, with icons). |
| **Segmented pill control** | Container `bg-surface-container-low p-1 rounded-full shadow(soft)`, options `px-6 py-2 rounded-full label-md`; selected `bg-primary text-on-primary shadow-sm` (Weekly/Monthly/All-time). |
| **Filter chip row** | Horizontal scroll, `hide-scrollbar`; chip `rounded-full px-4 py-1.5 label-md`; default `bg-surface-container-lowest shadow-sm` (or `bg-surface border border-outline-variant`); selected `bg-primary/10 text-primary border border-primary/20` (or `bg-primary-container text-on-primary-container`). |
| **Transactional chrome** | Auth/wizard screens suppress main nav: minimal fixed header with brand + escape link ("Back to Login" / "Exit Flow" ghost button). |

### 2.2 Form patterns

| Pattern | As built |
|---|---|
| **Text input, v1 (bordered)** | `bg-surface border border-outline-variant rounded-lg`, leading icon absolutely positioned (`left-md`, `text-outline-variant`, turns `text-primary` via `group-focus-within`), `pl-[48px] pr-md py-md`. Focus: `ring-2 ring-primary border-primary`. Placeholder `text-outline`. |
| **Text input, V2 (borderless / Level-1 elevation)** | `bg-surface shadow-sm rounded-lg px-md py-sm border-none focus:ring-2 focus:ring-primary` — shadow instead of border, per spec. Big variant for price: `font-headline-md` text with absolute `$` prefix and ambient shadow. |
| **Search input (pill)** | `rounded-full bg-surface-container(-low) border-none` with leading `search` icon; inside nav pill: `focus-within:border-primary/30`. |
| **Field label** | v1 login: `label-caps uppercase tracking-wider text-on-surface-variant`; v1 register: `status-label text-on-surface`; V2: `label-sm` or `label-md text-on-surface-variant`, above field, `mb-xs/sm`. |
| **Password row** | Label row with right-aligned "Forgot Password?" link; trailing visibility-toggle icon button inside the field. |
| **Checkbox / radio** | Native, `h-4 w-4 text-primary focus:ring-primary border-outline-variant rounded`. Radio group rendered as a bordered list card: rows `p-md` separated by `border-b border-surface-container-low`, `hover:bg-surface-bright`. |
| **Select** | v1: native `appearance-none` styled like inputs + absolute `expand_more` trailing icon. Heatmap filter: plain bordered native select. |
| **Toggle switch (custom)** | `peer` pattern: track `w-10 h-5 rounded-full bg-surface-variant peer-checked:bg-primary shadow-inner`; knob `w-3 h-3 bg-white rounded-full left-1 top-1 peer-checked:translate-x-5`, 200ms. Labeled "Push"/"Email" per row. |
| **Selection cards (single-choice)** | Grid of buttons, icon + `label-md` stacked; idle `border border-outline-variant bg-surface`; **selected `border-2 border-primary bg-primary-fixed shadow-sm` with `text-primary` icon** (vehicle size picker). |
| **Amenity chips (multi-choice)** | `rounded-full bg-surface-container-high px-md py-sm label-md` with 16px leading icon, `hover:bg-surface-container`. |
| **Upload dropzone** | `border-2 border-dashed border-outline-variant rounded-xl min-h-[300px] bg-surface hover:bg-surface-container-low`, centered camera icon + helper text on `bg-surface/80 backdrop-blur-sm` chip; thumbnail strip `grid grid-cols-4 gap-md aspect-square rounded-lg` with `add` tile. |
| **Multi-step wizard** | Header: `Step n of 4` (`label-md` bold primary) + step name; track `h-1 bg-surface-container-high rounded-full` with `bg-primary` fill animated `width 0.4s`. Steps fade in. Footer: ghost "Back" (opacity-0 when disabled) + primary pill "Next Step"/"Publish Spot". Success step: 96px `bg-secondary-container` icon disc, `display-lg` headline, points card with flame icon. Register variant: 3-segment bar (`flex-1 h-1` segments, done = `bg-primary`). |
| **Form section card (settings)** | `bg-surface-container-lowest rounded-2xl p-xl shadow-sm border border-outline-variant/20`; header row `title-lg` + primary-tinted icon, `border-b border-surface-container-high pb-md`; footer right-aligned "Save Changes" primary pill. |
| **Submit buttons** | Full-width primary on auth (`py-md rounded-lg` + trailing `arrow_forward`); OAuth button: `bg-surface border border-outline-variant` with inline Google SVG; divider "or continue with" (line + centered label on card bg). |

### 2.3 Button variants (observed recipes)

| Variant | Recipe |
|---|---|
| Primary pill (default CTA) | `bg-primary text-on-primary rounded-full px-5 py-2 font-label-md shadow-sm/md` + hover (`bg-primary/90`, `bg-surface-tint`, `hover:shadow-md`, or `hover:opacity-90`) + `active:scale-95` |
| Primary block (forms) | same, `rounded-lg`/`rounded-xl`/`rounded-2xl`, `w-full`, larger type (`status-label`/`h4`/`title-lg`) |
| Secondary / tonal | `bg-surface-container text-on-surface hover:bg-surface-container-high` (View, Start Earning); or `bg-surface-container-high` chip-button |
| Outline | `border border-outline-variant text-on-surface hover:bg-surface-container rounded-full px-xl py-md` (Request Info) |
| Ghost / text | `text-on-surface-variant hover:text-on-surface` or `text-primary hover:underline` (Edit, View All, Back) |
| Destructive | `bg-error text-on-error rounded-full/xl hover:opacity-90` (Suspend Listing); soft variant `bg-error-container/50 text-error hover:bg-error-container` (Sign Out Everywhere) |
| Icon button | `w-10 h-10 rounded-full` — translucent on imagery (`bg-surface-container-lowest/90 backdrop-blur-md shadow-sm`), tinted on panels (`bg-surface-container hover:bg-surface-container-high`), ghost in nav (`hover:bg-surface-container`) |
| Inverted (on gradients) | `bg-white text-[#1e3a8a] rounded-full font-bold` (View Rewards) |

### 2.4 Card patterns

| Card | Anatomy |
|---|---|
| **Spot card (list)** | `bg-surface-container-lowest rounded-[1.5rem] shadow-xl border border-surface-container-highest overflow-hidden group hover-lift hover:shadow-2xl`. Image header `h-40/48` with zoom-on-hover; overlay trust badge (glass pill, top-left) + favorite icon button (top-right). Body `p-5`: title (`title-lg` bold, `group-hover:text-primary`) + freshness line (`label-sm text-secondary` with `history` icon) vs. right-aligned price (`headline-md` bold + `/ hr` in `label-sm`). Metadata row: `label-md` icon+text pairs (walk time, monitored). Footer above `border-t border-surface-container-highest`: host avatar (32px) + name/rating stack vs. "Book"/"View" pill. |
| **KPI / glass card (analytics)** | `.glass-card rounded-xl p-lg` (white 0.8 + blur 20 + ambient shadow). Top row: tinted icon tile (`p-sm rounded-lg bg-primary-container` etc.) vs. trend chip. Bottom: `label-md` caption + `display-lg` value. |
| **Bento card (landing)** | `rounded-3xl p-8 soft-shadow border border-outline-variant`, `md:col-span-2` for wide cells; tinted variants (`bg-primary-container`, `bg-secondary-container`) with oversized quarter-circle decorations that scale on hover; 48px icon tile `rounded-xl`. |
| **Contributor snippet (spot detail)** | `bg-surface-container-lowest border border-surface-container rounded-3xl p-lg shadow-sm`; 56px avatar, name + "Top 1%" tertiary badge, flame + "14 Day Streak" line, chevron icon button. |
| **Vehicle card (settings)** | `bg-surface rounded-xl p-md shadow-sm border border-transparent hover:border-primary/20`, corner accent `bg-primary/5 rounded-bl-full`; title + "PRIMARY" mini-chip, mono plate chip (`font-mono bg-surface-container-low rounded`), fuel-type caption, delete icon button (`hover:text-error hover:bg-error-container/20`). |
| **Notification item** | `bg-surface rounded-xl p-md flex gap-md shadow-sm hover:bg-surface-container-low`; 40px icon disc (role-tinted: `secondary-container`, `error-container`, `surface-variant`) or avatar; message (`body-md`, bold lead-in) + timestamp/category (`label-sm`); **unread: `border-l-4 border-primary` (or `border-error`) + `w-2 h-2 bg-primary` dot**; read state dims text to `on-surface-variant`. |
| **Case queue item (moderation)** | `p-md rounded-lg/xl`, status badge + relative time row, `title-lg` heading, `body-md line-clamp-2` excerpt, location footer; **selected: `bg-surface-container-high` + absolute 4px `bg-primary` left bar**; idle: `bg-surface hover:bg-surface-container`. |
| **Stat tile (impact/trust)** | `bg-surface-container-lowest border border-surface-container rounded-xl p-5/6 text-center shadow-sm hover:shadow-md`; 36px tinted icon, `display-lg` number, `label-sm uppercase tracking-wider` caption. |
| **Achievement card** | Centered: 80px gradient icon disc (`from-{color}/20 to-{color}/5 border-2 border-{color}/20`), `title-lg` bold name, `body-md` description, "Unlocked" date mini-pill (`text-xs bg-{color}/10 rounded-full`). **Locked: `bg-surface-container opacity-70 grayscale`, outline-tinted disc with `lock` icon, thin progress bar instead of date.** |
| **Podium card (leaderboard top 3)** | Avatar 96–128px `border-4 border-surface` + deep shadow (+ `ring-4 ring-primary-container` for 1st), medal disc badge offset top-right; card below with name, points (`headline-md/lg text-primary`), "POINTS" caption; heights staggered (h-28/40/48), `group-hover:-translate-y-1/2`. |
| **Trust score card** | White card, ambient shadow, blurred color blobs; 192px SVG ring (track `text-surface-variant`, fill `text-primary`, `stroke-width 6`, animated dashoffset); `display-lg` score; rank pill `bg-secondary/10 text-secondary title-lg`. |
| **Streak card** | `bg-gradient-to-br from-[#fff7ed] to-[#ffedd5] border border-[#fed7aa] rounded-xl`; giant 120px translucent flame backdrop; 64px white/60 disc + flame; `headline-lg` in `#9a3412`; week row of 32px day discs (done = solid `#ea580c` white text, today = white + 2px orange border, future = `#ffedd5`/`#fdba74`). |
| **Celebration banner** | `bg-gradient-to-r from-[#1e3a8a] to-[#3b82f6] text-white rounded-2xl` + blue glow shadow; giant translucent trophy backdrop; glass icon disc (`bg-white/20 backdrop-blur-md border-white/30`); eyebrow `label-sm uppercase tracking-widest`; white inverted CTA pill. |

### 2.5 Map patterns

| Pattern | As built |
|---|---|
| **Layouts** | (a) Full-bleed map + floating right glass sidebar `lg:w-[400px]`, `rounded-l-[2rem]`, slide-in animation (map v3); (b) 60/40 split: map flex-1 + `md:w-[480px]` white detail pane, `md:rounded-l-3xl` (spot detail); mobile: detail pane becomes bottom sheet `h-[70vh] rounded-t-3xl` with drag handle. |
| **Price-pill marker** | `rounded-full px-3 py-1.5 font-label-md font-bold shadow-lg` + 45°-rotated 12px square caret beneath (`rounded-sm`). **Active: `bg-primary text-on-primary shadow-xl` + `.pulse-glow`** (2s primary ring). Default: `bg-surface-container-lowest border border-outline-variant/20` with role-tinted 14px icon (`verified` secondary, `ev_station` primary); hover: border/text takes the role color, `scale(1.1)`. |
| **Teardrop pin (selected spot)** | 48px `bg-primary rounded-full` disc + 16px white center dot + CSS-triangle tail, `animate-bounce`, deep shadow. Wizard variant: 48px `location_on` FILL-1 icon in primary + "Drag to adjust" tooltip chip; `cursor: grab/grabbing`, `active scale(0.95)`. |
| **Map controls** | Bottom-right stack: 40px circular white (`bg-surface-container-lowest shadow-lg`) `my_location` button + pill-stacked zoom group (`+`/`−` sharing a `rounded-full` container, divided by `border-outline-variant/20`); offset `lg:right-[420px]` to clear sidebar. Spec note: keep ≥80px from bottom-sheet handles. |
| **Glass overlays on map** | Search bar: `bg-surface/90 backdrop-blur-md rounded-lg shadow-md` strip with `search` icon (wizard); badge overlays on photos: `bg-white/95 backdrop-blur-md rounded-full px-md py-sm shadow-md`. |
| **Marker semantics (spec)** | Active = large blue pin w/ white dot; Filled = smaller slate pin; special spots embed icons (EV, accessible). |

### 2.6 Status & feedback patterns

| Pattern | Recipe |
|---|---|
| **Soft status badge (canonical)** | `rounded-full px-2 py-1` (or `px-sm py-xs`) `font-label-sm`, **background = status color at 10–30% + text = status color solid**, optional 12–16px leading icon. Observed: Verified `bg-secondary/10 text-secondary` + `verified` icon; Urgent `bg-error/10 text-error` + dot; In Review `bg-tertiary-container/20 text-tertiary` + dot; Pending `bg-on-surface-variant/10`; Standard `bg-surface-variant text-on-surface-variant`; eyebrow variant `uppercase tracking-wider` ("REPORTED LISTING"). |
| **Solid badge** | `bg-secondary-container text-on-secondary-container` ("PRIMARY", "4 spots near"); `bg-tertiary-container text-on-tertiary-container` ("Top 1%"); count chips `bg-error-container text-on-error-container` ("12 Urgent"). |
| **Status dot** | `w-2 h-2 rounded-full bg-{status}` — inside badges, unread indicator, bell notification dot (`bg-error`, absolute top-right). |
| **Left accent bar** | `border-l-4 border-primary` (unread notification), `border-error` (moderation alert), absolute `w-1 bg-primary` (selected case). |
| **Trend chip** | `flex gap-xs px-sm py-1 rounded-full label-sm` + `trending_up/down` 14px icon; positive `bg-secondary-container/20 text-secondary`; negative `bg-error-container/50 text-on-error-container`. |
| **Progress bars** | Track `bg-surface-container(-high)` / `bg-surface-variant`, `rounded-full overflow-hidden`; heights `h-1` (metrics, wizard), `h-1.5` (achievement), `h-2`–`h-2.5` (XP); fill `bg-primary` or gradient `from-primary to-primary-fixed(-dim)`, optional white-glow cap. Caption row: `label-sm` "Level Progress" vs "850 / 1000 XP". |
| **Verification timeline** | Vertical 2px line (`bg-surface-container-high`/`surface-variant`, CSS `::before`); 24px nodes — confirmed `bg-secondary text-on-secondary` check, passive `bg-surface-container-highest border-2 border-white`, tinted `bg-{color}/20` + FILL icon; entry = `body-lg` (bold actor) + `label-md text-outline` timestamp; optional "+0.2 Trust" chip per entry. |
| **Empty state** | Centered: 128px `bg-surface-container-high` disc with 64px primary icon, `headline-md` title ("All caught up!"), `body-md text-on-surface-variant max-w-sm` copy. |
| **Toast (spec)** | Floating, 16px from top, Level-2 elevation, status-colored leading icon. |
| **Skeleton loading** | WebGL shimmer shader on surface color (see §1.6). |
| **Chart tooltip** | `bg-inverse-surface text-inverse-on-surface label-sm px-2 py-1 rounded`, shown on bar hover. |
| **Destructive confirmation copy** | Destructive action buttons carry inline explanation text ("Destructive action: Immediately removes listing…, refunds user, and applies a strike"). |

### 2.7 Layout patterns

- **App shell (desktop):** fixed 256px sidebar + `ml-64` main canvas, or fixed top nav (`h-16`) + `mt-16` content. Mobile: top mini-header + `pb-24` content + fixed bottom nav.
- **Auth split:** centered `max-w-[1000px–1200px]` white card, `md:rounded-2xl shadow-lg overflow-hidden`, 50/50: photo pane (gradient overlay + glass trust panel + avatar stack) | form pane (`max-w-md mx-auto`). Mobile: form only, brand header on top. Ambient page decoration: huge blurred `bg-primary/5`/`bg-secondary/5` circles fixed behind.
- **Dashboard grid:** `grid-cols-12` with `gap-xl`; KPI row `md:grid-cols-3 gap-lg`; chart 2/3 + activity 1/3 (`lg:grid-cols-3`).
- **Master-detail (moderation v2):** queue pane `w-1/3` + detail pane flex-1, each a rounded elevated card inside a padded canvas; sticky action footer in detail pane.
- **Tri-pane (moderation v3):** `w-80` queue | flex-1 context | `w-[500px]` evidence+actions, flat columns separated by hairlines (`border-outline-variant/10`) on `bg-surface-container-lowest`.
- **Settings:** sticky in-page nav (col-span-3) + stacked section cards (col-span-8/9), two-up bento for Privacy/Security.
- **Centered feed:** `max-w-3xl`–`max-w-5xl mx-auto` column (notifications, contribution hub, wizard).
- **Sticky elements:** page headers (`sticky top-0 backdrop-blur-xl bg-background/80`), sheet action footers (`bg-surface/95 backdrop-blur-xl` + up-shadow), `pb-safe` on mobile bars.
- **Page header recipe:** `headline-lg` title + `body-md text-on-surface-variant` subtitle; optional right-side actions (filter pill + primary pill); optional eyebrow (icon + `label-md uppercase tracking-widest` in role color).
- **Decoration vocabulary:** blurred color blobs (`rounded-full blur-2xl/3xl` at 5–10% opacity), oversized translucent icons as card backdrops, quarter-circle corner accents, gradient scrims over photos (`from-inverse-surface/90 via-40% to-transparent`).
- **Scrollbars:** hidden (`.hide-scrollbar`) for horizontal chip rows and panels, or styled thin (6–8px, `#c2c6d8` thumb, transparent track).

---

## 3. Tailwind Theme Configuration

Consolidated from the per-screen configs (V2 canonical; matches what the screens actually extend):

```ts
// tailwind.config.ts
import type { Config } from "tailwindcss";

export default {
  darkMode: "class",
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // Primary
        primary: "#0050cb",
        "on-primary": "#ffffff",
        "primary-container": "#0066ff",
        "on-primary-container": "#f8f7ff",
        "primary-fixed": "#dae1ff",
        "primary-fixed-dim": "#b3c5ff",
        "on-primary-fixed": "#001849",
        "on-primary-fixed-variant": "#003fa4",
        "inverse-primary": "#b3c5ff",
        "surface-tint": "#0054d6",
        // Secondary (V2: emerald / success)
        secondary: "#006c49",
        "on-secondary": "#ffffff",
        "secondary-container": "#6cf8bb",
        "on-secondary-container": "#00714d",
        "secondary-fixed": "#6ffbbe",
        "secondary-fixed-dim": "#4edea3",
        "on-secondary-fixed": "#002113",
        "on-secondary-fixed-variant": "#005236",
        // Tertiary (V2: amber / warning, streaks)
        tertiary: "#7f4f00",
        "on-tertiary": "#ffffff",
        "tertiary-container": "#a06500",
        "on-tertiary-container": "#fff7f1",
        "tertiary-fixed": "#ffddb8",
        "tertiary-fixed-dim": "#ffb95f",
        "on-tertiary-fixed": "#2a1700",
        "on-tertiary-fixed-variant": "#653e00",
        // Error
        error: "#ba1a1a",
        "on-error": "#ffffff",
        "error-container": "#ffdad6",
        "on-error-container": "#93000a",
        // Surfaces & neutrals
        background: "#f8f9ff",
        "on-background": "#0b1c30",
        surface: "#f8f9ff",
        "surface-bright": "#f8f9ff",
        "surface-dim": "#cbdbf5",
        "surface-container-lowest": "#ffffff",
        "surface-container-low": "#eff4ff",
        "surface-container": "#e5eeff",
        "surface-container-high": "#dce9ff",
        "surface-container-highest": "#d3e4fe",
        "surface-variant": "#d3e4fe",
        "on-surface": "#0b1c30",
        "on-surface-variant": "#424656",
        outline: "#727687",
        "outline-variant": "#c2c6d8",
        "inverse-surface": "#213145",
        "inverse-on-surface": "#eaf1ff",
      },
      borderRadius: {
        DEFAULT: "0.25rem",
        lg: "0.5rem",
        xl: "0.75rem",
        // 2xl (1rem) and 3xl (1.5rem) keep Tailwind defaults — both used heavily
        full: "9999px",
      },
      spacing: {
        base: "4px",
        xs: "4px",
        sm: "8px",
        md: "16px",
        lg: "24px",
        xl: "32px",
        "2xl": "48px",            // v1 screens
        gutter: "16px",
        "container-margin": "20px", // V2 mobile safe margin
        "margin-mobile": "16px",    // v1 auth screens
        "margin-desktop": "32px",   // v1 auth screens
      },
      fontFamily: {
        sans: ["Inter", "sans-serif"],
      },
      fontSize: {
        "display-lg": ["48px", { lineHeight: "1.1", letterSpacing: "-0.02em", fontWeight: "700" }],
        "headline-lg": ["32px", { lineHeight: "1.2", letterSpacing: "-0.02em", fontWeight: "700" }],
        "headline-lg-mobile": ["24px", { lineHeight: "1.2", fontWeight: "700" }],
        "headline-md": ["24px", { lineHeight: "1.3", letterSpacing: "-0.01em", fontWeight: "600" }],
        "title-lg": ["20px", { lineHeight: "1.4", fontWeight: "600" }],
        "body-lg": ["16px", { lineHeight: "1.6", fontWeight: "400" }],
        "body-md": ["14px", { lineHeight: "1.5", fontWeight: "400" }],
        "label-md": ["12px", { lineHeight: "1", letterSpacing: "0.01em", fontWeight: "600" }],
        "label-sm": ["11px", { lineHeight: "1", fontWeight: "500" }],
      },
      boxShadow: {
        soft: "0px 4px 20px rgba(0, 0, 0, 0.05)",   // Level 1 / ambient-sm
        deep: "0px 12px 40px rgba(0, 0, 0, 0.1)",   // Level 2 / ambient-md
        "sheet-left": "-10px 0px 40px rgba(0, 0, 0, 0.08)",
        "sheet-up": "0px -10px 40px rgba(0, 0, 0, 0.05)",
        "nav-up": "0px -4px 20px rgba(0, 0, 0, 0.05)",
        lift: "0 12px 24px rgba(0, 0, 0, 0.1)",      // hover-lift
        glow: "0px 8px 30px rgba(59, 130, 246, 0.3)", // celebration
      },
      transitionDuration: {
        fast: "100ms",
        std: "250ms",
        fluid: "400ms",
      },
      transitionTimingFunction: {
        spring: "cubic-bezier(0.34, 1.56, 0.64, 1)",
      },
      keyframes: {
        "pulse-glow": {
          "0%":   { boxShadow: "0 0 0 0 rgba(0, 80, 203, 0.4)" },
          "70%":  { boxShadow: "0 0 0 10px rgba(0, 80, 203, 0)" },
          "100%": { boxShadow: "0 0 0 0 rgba(0, 80, 203, 0)" },
        },
        "slide-in-right": {
          from: { transform: "translateX(100%)", opacity: "0" },
          to:   { transform: "translateX(0)", opacity: "1" },
        },
        "fade-in-up": {
          from: { opacity: "0", transform: "translateY(10px)" },
          to:   { opacity: "1", transform: "translateY(0)" },
        },
        float: {
          "0%, 100%": { transform: "translateY(0)" },
          "50%":      { transform: "translateY(-10px)" },
        },
      },
      animation: {
        "pulse-glow": "pulse-glow 2s infinite",
        "slide-in-right": "slide-in-right 400ms cubic-bezier(0.34, 1.56, 0.64, 1) forwards",
        "fade-in-up": "fade-in-up 0.3s ease-in-out forwards",
        float: "float 6s ease-in-out infinite",
      },
    },
  },
  plugins: [require("@tailwindcss/forms"), require("@tailwindcss/container-queries")],
} satisfies Config;
```

Companion CSS utilities used by the screens (keep as `@layer utilities` / components):

```css
.glass-panel {
  background: rgba(248, 249, 255, 0.7); /* 0.85–0.95 variants in situ */
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.3);
}
.dark .glass-panel { background: rgba(11, 28, 48, 0.85); }

.hover-lift { transition: all 250ms cubic-bezier(0.34, 1.56, 0.64, 1); }
.hover-lift:hover { transform: translateY(-4px); box-shadow: 0 12px 24px rgba(0,0,0,0.1); }

.hide-scrollbar::-webkit-scrollbar { display: none; }
.hide-scrollbar { -ms-overflow-style: none; scrollbar-width: none; }

/* Icon font states */
.material-symbols-outlined { font-variation-settings: 'FILL' 0, 'wght' 400, 'GRAD' 0, 'opsz' 24; }
.material-symbols-outlined.filled { font-variation-settings: 'FILL' 1; }
```

Notes:
- The mockups apply type as the **pair** `font-{token} text-{token}` (e.g. `font-title-lg text-title-lg`) because Stitch registers each token in both `fontFamily` and `fontSize`. With the single `fontFamily.sans` above, `text-{token}` alone is sufficient.
- If shadcn/ui is adopted, mirror the color tokens as CSS variables (HSL) and point the Tailwind colors at `hsl(var(--…))` — mapping in §4.

---

## 4. shadcn/ui Mapping

Token bridge (semantic shadcn variables ← Parkio tokens):

| shadcn CSS var | Parkio token |
|---|---|
| `--background` | `background` `#f8f9ff` |
| `--foreground` | `on-background` `#0b1c30` |
| `--card` / `--popover` | `surface-container-lowest` `#ffffff` |
| `--card-foreground` | `on-surface` `#0b1c30` |
| `--primary` / `--ring` | `primary` `#0050cb` |
| `--primary-foreground` | `on-primary` `#ffffff` |
| `--secondary` | `surface-container` `#e5eeff` (tonal button bg) |
| `--secondary-foreground` | `on-surface` `#0b1c30` |
| `--muted` | `surface-container-low` `#eff4ff` |
| `--muted-foreground` | `on-surface-variant` `#424656` |
| `--accent` | `surface-container-high` `#dce9ff` (hover bg) |
| `--accent-foreground` | `on-surface` `#0b1c30` |
| `--destructive` | `error` `#ba1a1a` |
| `--destructive-foreground` | `on-error` `#ffffff` |
| `--border` | `outline-variant` `#c2c6d8` (usually at 10–50% alpha) |
| `--input` | `outline-variant` `#c2c6d8` |
| `--radius` | `0.5rem` (standard UI radius) |
| extra: `--success` | `secondary` `#006c49` |
| extra: `--warning` | `tertiary` `#7f4f00` |

Component mapping:

| Parkio pattern | shadcn/ui base | Customization required (to match mockups) |
|---|---|---|
| Buttons (§2.3) | `Button` | Add `rounded-full` pill default for CTAs; variants: `default`, `tonal` (surface-container), `outline`, `ghost`, `destructive`, `destructive-soft`; `active:scale-95`; `shadow-sm → hover:shadow-md` |
| Text/search inputs | `Input` | Two styles: bordered (v1 auth) and borderless+`shadow-soft` (V2); leading-icon wrapper; pill variant for search |
| Field labels | `Label` | `label-sm`/`label-md` variant + `uppercase tracking-wider` option |
| Select (vehicle type, ranges) | `Select` | Pill or bordered trigger, `expand_more` icon |
| Checkbox / TOS | `Checkbox` | 16px, primary check |
| Privacy radio list | `RadioGroup` | List-card layout (rows with hairline dividers, hover bg) |
| Push/Email toggles | `Switch` | Small size: 40×20 track, 12px thumb, `surface-variant` off-track |
| Status/trust badges | `Badge` | New variants: `soft-{success,warning,danger,neutral}` = `bg-{color}/10 text-{color} rounded-full` + icon slot; `solid-container` variants |
| Cards (all §2.4) | `Card` | Radius per tier (`xl`/`2xl`/`3xl`/`[1.5rem]`), `shadow-soft`, border `outline-variant/20` or `surface-container-*` |
| Queue/content tabs | `Tabs` | Underline style (`border-b-2 border-primary`); segmented-pill style for time filters |
| Avatars + stacks | `Avatar` | Sizes 32/40/56/96/128; `border-2 border-surface` for overlap stacks (`-space-x-2/-4`) |
| XP / metric bars | `Progress` | Heights 4–10px; gradient fill `from-primary to-primary-fixed-dim` |
| Spot detail mobile sheet / bottom sheet | `Sheet` (or vaul `Drawer`) | `rounded-t-3xl`, drag-handle bar (48×6 `bg-outline-variant rounded-full`), glass footer |
| Moderation confirm, dialogs | `Dialog` / `AlertDialog` | `rounded-2xl`, `shadow-deep`; destructive copy block pattern |
| Toasts (spec) | `Sonner` / `Toast` | Top-positioned, status icon, Level-2 elevation |
| Notifications "Mark all read", profile menu | `DropdownMenu` | Standard |
| Chart bar tooltips | `Tooltip` | `bg-inverse-surface text-inverse-on-surface` |
| Skeletons | `Skeleton` | Surface-tinted; optional shimmer (CSS gradient stand-in for the WebGL shader) |
| Leaderboard table | `Table` | Borderless rows, `divide-surface-container-low`, hover tint, CSS-grid column template |
| Settings anchor nav / sidebar | (custom; optionally `NavigationMenu`) | Active = `bg-primary-container` pill / `border-r-4` rail |
| Hairlines | `Separator` | `outline-variant` at low alpha |
| Filter chips, amenity chips | (custom on `Toggle`/`ToggleGroup`) | Pill chips, selected = `bg-primary/10 text-primary border-primary/20` |
| Scrollable chip rows / panes | `ScrollArea` | Hidden or thin styled scrollbar |
| Wizard | (custom; no shadcn primitive) | Progress header + step transitions per §2.2 |
| Map markers/controls | (custom over MapLibre/Google) | §2.5 recipes |

---

## 5. Reusable React Component List

Derived strictly from repetition across the 14 screens. Suggested location: `frontend/packages/ui` (primitives) and `frontend/apps/web/src/components` (domain).

### Layout / shell
1. `AppShell` — sidebar (desktop) / bottom-nav (mobile) switch, content canvas
2. `SideNav` — brand + tagline, nav items w/ active rail, footer CTA + user snippet
3. `TopNav` — fixed glass bar variant; `FloatingNavPill` variant (map screen)
4. `BottomNav` — 5-tab mobile bar, active pill tab
5. `PageHeader` — title/subtitle/eyebrow + actions slot, sticky-blur option
6. `AuthSplitLayout` — photo pane (gradient + glass panel) | form pane
7. `MasterDetailLayout` — queue/detail (2-pane) and queue/context/actions (3-pane)
8. `BottomSheet` — mobile sheet w/ drag handle, sticky glass footer
9. `GlassPanel` — glassmorphism container primitive

### Primitives (shadcn-backed, themed)
10. `Button` (variants per §2.3) · 11. `IconButton` · 12. `Input` (bordered/elevated/pill, icon slots) · 13. `Label` · 14. `Select` · 15. `Checkbox` · 16. `RadioListGroup` · 17. `Switch` (small) · 18. `Badge` (soft/solid status variants) · 19. `Chip` (filter/amenity, selectable) · 20. `SegmentedControl` · 21. `Tabs` (underline) · 22. `Avatar` + `AvatarStack` · 23. `ProgressBar` (gradient option) · 24. `Card` (tiered radius/shadow) · 25. `Separator` · 26. `Tooltip` · 27. `Skeleton` (shimmer) · 28. `EmptyState` · 29. `Dialog`/`AlertDialog` · 30. `Toast`

### Domain — parking & map
31. `SpotCard` — image header, trust-badge overlay, favorite, price, host footer
32. `ProductCard` — shared app-level list/card surface; consistent padding, radius, hover/focus, selected state
33. `SpotResultCard` — real `PublicSpot`/owner `Spot` fields only; map and my-spots row density
34. `MapCanvas` — map container + overlay slots
35. `PriceMarker` — pill marker w/ caret, active/pulse/role-icon states
36. `DropPin` — teardrop pin (bounce) / draggable wizard pin w/ tooltip
37. `MapControls` — locate button + zoom stack
38. `MapSearchOverlay` — glass search strip
39. `AttributeChip` — bordered icon chip (SUV Friendly, EV Nearby, 2hr Max)
40. `SpotDetailPanel` — gallery, header (address/price/walk), attributes, contributor, timeline, sticky CTA footer
41. `VerificationTimeline` + `TimelineItem`
42. `ContributorSnippet` — avatar, rank badge, streak line

### Domain — gamification & community
43. `KpiCard` (glass) + `TrendChip`
44. `StatTile` (centered icon/number/caption)
45. `XpProgressCard` — labeled gradient bar + remaining-XP caption
46. `StreakCard` — orange gradient + week-day discs
47. `CelebrationBanner` — gradient milestone banner
48. `AchievementCard` (unlocked/locked)
49. `LeaderboardPodium` (top-3, medal badges)
50. `LeaderboardRow` — rank, initial, public-profile label, points, level/trust badges
51. `LeaderboardTable` (rank-delta arrows, trust badges, sticky "You" row)
52. `ContributionHeatmap` (5-step primary-alpha cells + legend)
53. `TrustScoreRing` (animated SVG ring)
54. `BarChart` (revenue bars + tooltip + legend dots) — or themed Recharts wrapper

### Domain — notifications, moderation, settings
55. `NotificationItemCard` (role-tinted disc, unread accent/dot, timestamp/action slot) + `NotificationFilters`
56. `CaseQueueItem` (status badge, excerpt, selected rail) + `CaseQueueList` w/ tabs
57. `CaseDetailHeader` (eyebrow badge, case id, parties)
58. `EvidenceCompare` (labeled photo frames, warning variant)
59. `ResolutionActions` (dismiss / destructive-with-explanation / ghost)
60. `SettingsSectionCard` (icon heading, description, content, optional action)
61. `SettingsAnchorNav`
62. `NotificationPrefRow` (text block + Push/Email switches)
63. `VehicleCard` + `PlateChip`
64. `SecurityRow` (label/status/action)

### Domain — forms & flows
65. `StepWizard` — progress header, animated steps, Back/Next footer, success step
66. `UploadDropzone` + `ThumbnailGrid`
67. `SelectionCardGroup` (vehicle size single-choice cards)
68. `PriceInput` (headline-size, `$` prefix)
69. `PasswordInput` (visibility toggle, forgot-link slot)
70. `OAuthButton` (Google)
71. `FormDivider` ("or continue with")
72. `TosCheckbox`

---

### Source screen index

| Screen | File | Theme gen |
|---|---|---|
| Landing page | `landing_page_desktop` | V2 |
| Login | `login_desktop` | v1 |
| Register | `register_desktop` | v1 |
| Map search (motion) | `map_experience_v3_motion_enhanced` | V2 |
| Spot detail | `spot_detail_v3_premium_showcase` | V2 |
| Spot creation wizard | `spot_creation_flow_desktop` | V2 |
| Notifications center | `notifications_center_desktop` | V2 |
| Leaderboard | `community_leaderboard_desktop` | V2 |
| Contribution hub | `contribution_hub_v3_impact_journey` | V2 |
| Reputation / trust center | `reputation_trust_center_desktop` | V2 |
| Analytics dashboard | `analytics_v2_desktop` | V2 |
| Moderation (2-pane) | `moderation_v2_desktop` | V2 |
| Community safety (3-pane) | `community_safety_v3_moderation_hub` | V2 |
| Settings & preferences | `settings_preferences_desktop` | V2 |
| Loading shimmer shader | `shader` | — |
