# Parkio — Project Definition (for design-prompt work)

> Prepared 2026-07-17 from the actual repository state (`v1.0.0-rc1` line, master branch).
> Purpose: give a designer / design-prompt author complete, honest context about what
> Parkio is, what exists today, and what visual/brand system it uses.

---

## 1. What Parkio is

**One-liner:** Parkio is a community-powered parking intelligence platform that helps
drivers find, share, verify, and manage real-world parking availability.

**Tagline:** "Community-powered parking intelligence for real-world availability."

**30-second pitch:** Drivers can navigate to a destination, but they still don't know
whether a nearby parking spot is available, legal, recent, or suitable for their car.
Parkio turns fresh driver observations into structured parking signals: a driver shares
a spot with a photo and context; nearby drivers discover it, verify it, claim it after
parking, or report problems. Trust scores, gamification, moderation, and advisory AI
validation keep the signal useful. Smart Return (opt-in) helps drivers manage the
return trip to their parked car.

**What Parkio is NOT (explicit brand positioning):**
- Not a parking payment app — there is no pricing, booking, or payment anywhere in the product.
- Not a navigation app or a map clone.
- Not a legal authority — it carries user-reported legal/risk context but never guarantees a spot is legal or safe.

## 2. Problem

- Parking decisions are made with stale guesses: a spot may be free, blocked, illegal, risky, or already taken.
- Availability changes in minutes; static maps and navigation apps don't cover the curb.
- Local parking knowledge evaporates instead of helping the next driver.
- The people closest to the curb have the freshest signal — there's no structured way to share it.

## 3. Solution — the core loop

1. **Share:** a driver uploads a spot photo + location + description + vehicle fit + parking context + legal-status signals.
2. **Discover:** nearby drivers find spots on a map (PostGIS-backed nearby search) on web or mobile.
3. **Trust:** the community verifies availability, claims a spot after parking (strong usefulness signal), or reports problems (wrong location, illegal, fake, occupied, offensive).
4. **Keep it clean:** moderation, trust/contribution scores, and advisory AI photo validation (Gemini vision) keep unreliable or risky data from being treated as truth.
5. **Return:** opt-in Smart Return assists the return trip with saved settings, expected return time, and notifications.

## 4. Target users

- **Primary:** drivers who repeatedly hunt for parking in dense urban, campus, commuter, or visitor-heavy areas.
- **Early adopters:** drivers in ONE constrained geography willing to contribute observations (campus drivers, dense-neighborhood commuters, people already sharing parking tips informally).
- **Secondary audiences for the site:** beta testers, cloud startup program reviewers, angel/VC reviewers, technical due-diligence reviewers, future partners (campuses, venues, municipalities).
- **Locale note:** the product ships Turkish-default bilingual UI (TR default, EN secondary) — design must tolerate longer Turkish strings.
- **Initial geography:** the beta focus is İzmir, Türkiye — the shared geo package pins the default map center there and the default timezone is Europe/Istanbul. Public docs deliberately say "one constrained geography, TBD."

## 5. Current stage (honest status — brand rule: never overclaim)

- Release line `v1.0.0-rc1` (mobile app at `1.0.0-rc3`), hosted-beta release candidate. Application layer certified (backend 87/100, frontend 86/100 internal certification). Essentially every screen is implemented with co-located tests — this is a real product, not mockups.
- Public landing page for `parkio.dev` is ready; waitlist intake implemented.
- Public production NOT ready. No users/revenue/partnerships claimed. **Update 2026-07-18:** the hosted-beta API gateway is verifiably live at `https://api.parkio.dev` (health UP, auth stack responding end-to-end; see `PARKIO-API-REFERENCE.md`) — the repo docs describing it as "not yet deployed" predate this.
- Monetization: none during beta. Free community product; freemium/premium Smart Return/partnership dashboards are only future hypotheses.
- Copy discipline: allowed words — "community-powered, parking intelligence, photo-backed, verified, trust signals, hosted-beta release candidate, privacy-conscious." Forbidden until proven — "launched, production-ready, guaranteed parking, trusted by thousands, AI-powered legality decisions."

## 6. Product surfaces & screens (all implemented and tested, RC quality)

### 6.1 Web app (React SPA, light theme only today)

App shell: glass top nav on desktop, bottom tab bar on mobile web; authenticated home is the map.

| Route | Screen |
|---|---|
| `/login`, `/register`, `/forgot-password`, `/reset-password`, `/check-email`, `/verify-email` | Full auth suite on the "auth split" layout (photo pane + form pane); trace-id surfacing on errors |
| `/terms`, `/privacy` | Legal pages |
| `/map` | **Product home.** Full-bleed MapLibre canvas, glass search overlay (place typeahead via Nominatim, geolocate, radius/limit controls), desktop results sidebar vs. mobile draggable bottom sheet, selected-spot preview, filter/sort by status/vehicle/distance, Smart Return banner mode |
| `/spots/:spotId` | Spot detail: signed-URL photo hero, trust/status panel, attribute chips, "community signal" section, location map, sticky action card with **Verify / Claim as filled / Report** |
| `/upload` | **4-step spot-creation wizard:** Photo (drag/drop, single image) → Location (place search + map picker + manual coords) → Details (vehicle types, parking context, legal status, violation flags) → Review → Success. Idempotency keys + unsaved-changes guard |
| `/my-spots` | The user's shared spots with status badges; empty state routes to upload |
| `/profile` | Settings & impact hub: identity hero (trust/level/points) + section rail — Account, Vehicle, Notifications, Smart Return (feature-flagged), Trust & Progress |
| `/gamification` | "Your Impact": level/points hero, recent point transactions, current benefits (search radius, result limit, daily views, priority perks), full level roadmap |
| `/leaderboard` | Top contributors, podium top-3 with medals, show-more pagination |
| `/notifications` | Notification feed with filter chips (all/unread/moderation/gamification), mark-read, deep links |
| `/reports` | "My reports" + appeal submission |
| `/admin/moderation` | Moderator case queue: open/in-review/resolved, severity, assign/review/resolve, appeals (moderator+) |
| `/admin` suite | Admin-only shell with sidebar: Dashboard (KPIs), Users (list + detail with suspend/restore, session revoke, role grants), Security snapshot, Analytics (platform KPIs + tables), Audit log, System info |

### 6.2 Mobile app (Expo / React Native, light + dark themes)

- Bottom tabs: **Map · My spots · Share · Leaderboard · Profile** ("Share" opens the upload modal).
- Full auth screens; spot detail; **camera-first upload flow** (in-app camera or gallery → preview → image prep → upload with progress, cancel, retry, offline auto-retry); spot-creation wizard with GPS-accuracy gating, center-pin location adjust, and draft persistence across cold starts.
- Map is MapLibre GL running inside a WebView (key-free OSM raster fallback, GPU clustering, "search this area", recent searches, permission card, Smart Return banner, spot bottom sheet).
- Impact (gamification), notifications (with push deep links), reports, Smart Return settings + "today" card, profile hub (vehicle, preferences, change password, about), staff moderation queue + analytics. No full admin panel on mobile.

### 6.3 Landing page & waitlist (parkio.dev)

- Marketing/waitlist site is a static build deployed separately (Hostinger artifact in the repo); the product app lives behind auth.
- Waitlist intake posts to the gateway: `{ email, city?, role: driver|tester|partner, consent }`, rate-limited, honest hosted-beta framing.

### 6.4 Shared frontend packages (pnpm workspace)

`@parkio/api-client` (all domain clients, correlation IDs, idempotency keys, single-flight token refresh) · `@parkio/types` (backend DTO mirrors) · `@parkio/validation` (zod schemas shared by web+mobile forms) · `@parkio/geo` (distance/format helpers, spot presentation model with availability/confidence tiers, key-free map style builder, İzmir default center) · `@parkio/ui` (minimal real component library for web: Button, Input, Card, Badge/SoftBadge/StatusBadge, MetricCard, EmptyState, Loading/Skeleton set, centralized spot-status visuals; the mobile app has its own native UI kit mirroring the same tokens).

## 7. Core features in detail (as actually modeled in the backend)

### 7.1 Parking spots — ephemeral "available now" signals, not listings

A spot is a short-lived availability signal with exactly **one photo**:

- Fields: location (lat/lng + optional pin adjustment flag), address text, description (≤1000 chars), suitable vehicle types (`SEDAN, HATCHBACK, SUV, VAN, MOTORCYCLE, ANY`), parking context (`STREET_PARKING, OPEN_PARKING_LOT, INDOOR_PARKING, MALL_PARKING, RESIDENTIAL_AREA, OFFICE_AREA, UNKNOWN`), legal status (`LEGAL, UNCERTAIN, ILLEGAL_OR_RISKY` — illegal/risky submissions are rejected at creation), advisory violation-reason flags (no-parking sign, garage entrance, bus stop, crossing, hydrant, sidewalk, traffic-blocking, private property, other), confidence score, verification count, expiry time.
- Status lifecycle: `PENDING_VALIDATION → (AI gate) → ACTIVE → VERIFIED / SUSPICIOUS → FILLED / EXPIRED / REJECTED`, plus `PENDING_REVIEW` when AI flags a warning. Only `ACTIVE` and `VERIFIED` spots are publicly discoverable.
- **Freshness is minutes, not days:** a new spot is valid ~10 minutes; each availability verification extends it (15 then 20 min). Two "filled" reports flip it to `FILLED`. A background job expires stale spots every minute. **Design implication: countdowns/freshness indicators are core content.**
- Privacy: non-owners see a sanitized public view (no owner ID, no internal counters); owners manage their own spots via "my spots".

### 7.2 Publication gating (photo-first quality funnel)

1. Photo must upload and pass ClamAV malware scanning (media `READY`) before a spot can even be created.
2. New spots start `PENDING_VALIDATION` (not discoverable). Gemini vision AI analyzes the photo: is this a real, plausibly available parking location (not a screenshot, not "just a car," not too dark/blurry)?
3. AI verdict gates publication: `PASSED → ACTIVE` (live on map), `WARNING → PENDING_REVIEW` (held for moderation), `FAILED / NOT_A_PARKING_SPOT → REJECTED`. AI outages fail closed (spot stays pending). AI is advisory for moderation; the parking service enforces the gate.

### 7.3 Verify / claim / report

- **Verify** (non-owners only, once per spot): result is one of `AVAILABLE, FILLED, INVALID, ILLEGAL_OR_RISKY, WRONG_VEHICLE_SIZE`. "Available" upgrades the spot to `VERIFIED`, extends its life, and rewards the owner. Negative results dent confidence; illegal/risky opens a moderation case.
- **Claim** (non-owners): "I'm taking this spot" — marks it terminally `FILLED`, rewards owner (+30) and claimer (+10). **Free action — there is no booking or payment.**
- **Report:** reasons include fake/duplicate/old photo, wrong location, not a parking spot, illegal-or-risky, wrong vehicle size, private property, spam, abuse. Serious reasons open a moderation case immediately.

### 7.4 Money: none — this is definitive

A full sweep of all 10 services finds **zero** pricing/booking/payment/reservation/wallet concepts. Spots are free community shares; the only "currency" is gamification points. Any design showing prices, hourly rates, revenue, or "Book" buttons is off-model.

### 7.5 Gamification & trust (functional, not cosmetic)

- **Points:** upload +5 · your spot verified +20 (verifier +5) · your spot claimed +30 (claimer +10) · penalties for rejected/illegal spots (−25). Points never go below 0.
- **Levels gate real capability** — search radius, result count, and daily spot views grow with level: L1 300m/3 results/20 views → L5 2500m/25 results/300 views, with verified-spot and notification priority at L4–L5. Leveling up literally widens your map.
- **Trust Score** 0–100 (starts 100): +2 per verified spot, +1 per claim, −10 moderator rejection, −15 admin penalty. Shown as bands: `UNTRUSTED <25, LOW_TRUST, MEDIUM_TRUST, HIGH_TRUST ≥75`.
- **Leaderboard** of top contributors by points. Contribution Score = lifetime points.
- **Not modeled in the backend:** streaks, badges, achievements (the Stitch mockups show them — see §14).

### 7.6 Smart Return (opt-in, feature-flagged, privacy-first)

"Your parking near home may be freeing up" assistant: user sets a home location, default return time, and reminder lead (5–120 min). Morning in-app prompt asks "did you leave by car today?" (`LEFT_BY_CAR / NOT_BY_CAR / cancel`, editable return time). Near return-time-minus-lead, the system searches real spots near home and notifies **only if spots actually exist**. No movement history is retained. Default timezone Europe/Istanbul.

### 7.7 Notifications

- Types: `NEARBY_PARKING` (planned), `SMART_RETURN_PROMPT`, `SMART_RETURN_AVAILABLE`, `LEVEL_UP`, `POINT_EARNED`, `WARNING` (penalties/rejections/suspensions), `SYSTEM` (restorations, appeal/case resolutions).
- Channels: in-app inbox, push (Expo/FCM with per-device tokens for iOS/Android/Web), email — each user-toggleable. Templates localized TR (default) / EN; push carries deep-link routes.

### 7.8 Moderation & governance

- Case queue: `OPEN → IN_REVIEW → RESOLVED / dismissed`, severity LOW→CRITICAL, targets spot/user/media. Fed by serious user reports, community rejections, illegal/risky verifications, and AI warnings/failures.
- Moderator actions: approve/dismiss, reject spot, mark risky. **Admin-only actions:** reduce trust, deduct points, suspend/restore user. Actions flow through events; a strike ledger (`UserViolation`) records penalties; no auto-ban thresholds — suspension is a deliberate admin decision.
- **Appeals:** a penalized user can appeal a resolved case; admin resolves; accepted suspension appeals restore the account.

### 7.9 Accounts & roles

- Email + password only (**no OAuth/social login**), BCrypt, email verification required before login (`PENDING_VERIFICATION → ACTIVE`; suspended/banned cannot log in). Refresh-token rotation, session revocation; forgot/reset/change password flows.
- Roles: `USER, MODERATOR, ADMIN, SUPER_ADMIN`. Analytics dashboards are admin-only (platform overview, daily snapshots, parking funnel created→verified→claimed→rejected, per-user metrics). User-facing stats are limited to trust score/band, points, level.

## 8. Brand identity

- **Name/wordmark:** "Parkio", sentence case, normal tracking.
- **Official logo (integrated Jul 2026):** a vivid blue rounded "P" with a white car silhouette tilted inside the bowl of the P. Lockups: horizontal, stacked, symbol-only, wordmark-only. Works in monochrome. App icons/favicons use the symbol alone.
- **Personality:** Calm, Reliable, Friendly, Privacy-conscious, Technically strong. More calm than energetic; more practical than playful; more modern than corporate; more human than enterprise; more trustworthy than flashy.
- **Should feel like:** a reliable companion for uncertain parking moments; a community tool with strong technical guardrails; a modern mobility product that doesn't copy map apps.
- **Should NOT feel like:** a taxi/ride-hailing brand, a crypto project, a gamified arcade product, a dark cybersecurity product, a government portal, a generic SaaS dashboard.
- **Voice:** clear, honest, useful, human. Landing tone: concise, confident, calm. Beta tone: friendly, transparent, specific.
- **Brand north star:** "Clear parking decisions through trusted community signals."

## 9. Visual design system (as implemented / specified)

### 9.1 The two color generations — important context

- The older brand docs (`docs/brand/05-color-system.md`) defined a **teal** direction (Parkio Teal `#147C72`).
- The **shipped identity is blue**: the official logo, the app icons, and all 14 Stitch UI mockups + the implemented web/mobile UI use an electric/trustworthy **blue** Material-3-style system. Treat blue as canonical; the teal doc is historical.

### 9.2 Canonical tokens (Stitch "Parkio V2", Material-3-style roles, light theme first)

- **Primary (Electric Blue):** `primary #0050cb`, `primary-container #0066ff`, fixed tints `#dae1ff` / `#b3c5ff`, on-primary white. Used for CTAs, active nav, links, focus rings, active map markers.
- **Secondary (Verified Emerald):** `#006c49`, container `#6cf8bb` — "Verified" badges, success, positive trends.
- **Tertiary (Amber):** `#7f4f00`, container `#a06500` — warnings, "In Review", streaks/star accents.
- **Error:** `#ba1a1a`, container `#ffdad6` — urgent, destructive, reports.
- **Surfaces:** page `#f8f9ff` (blue-tinted near-white); card `#ffffff`; container ramp `#eff4ff → #e5eeff → #dce9ff → #d3e4fe`; text `#0b1c30` (navy ink); secondary text `#424656`; outline `#727687` / `#c2c6d8`; inverse surface `#213145`.
- **Status semantics:** Active=blue, Verified/success=emerald, Warning/in-review=amber, Filled/inactive=slate, Expired/urgent/rejected=red. Status is never color-only (icon + label required).
- **Dark mode:** secondary. The web app ships light-only today (`darkMode: 'class'` is configured but no dark screens exist); the mobile app already has semantic light/dark palettes. Light is the brand identity; dark must stay calm, never "hacker."

### 9.3 Typography & iconography

- **Font:** Inter (400/500/600/700) everywhere. (Brand docs allow Space Grotesk as a display alternative for marketing/hero moments only.)
- **Type scale (V2):** display-lg 48/1.1 700 −0.02em · headline-lg 32/1.2 700 · headline-lg-mobile 24 · headline-md 24/1.3 600 · title-lg 20/1.4 600 · body-lg 16/1.6 · body-md 14/1.5 (default) · label-md 12/600 · label-sm 11/500. Bold headlines with negative tracking; uppercase labels with wide tracking.
- **Icons:** Material Symbols Outlined variable font; FILL 0 default, FILL 1 for active states. Icon themes: parking, pin, search, camera, check/verify, flag/report, shield/privacy, bell, clock/Smart Return, community.

### 9.4 Shape, elevation, motion — "Sophisticated Minimalism / Tactile Modern"

- Design mantra from the Stitch specs: **"Concierge for the curb."** Spatial clarity over structural lines — borders are replaced by soft tonal surfaces and ambient shadows.
- **Radius:** 8px inputs/buttons · 12–16px cards · 24–32px premium cards/sheets · full-pill for badges, chips, markers, search bars, primary CTAs.
- **Shadows:** ambient soft `0 4px 20px rgba(0,0,0,.05)` (cards) and ambient deep `0 12px 40px rgba(0,0,0,.10)` (modals/sheets); directional sheet shadows; a blue glow only for celebration moments.
- **Glassmorphism** for anything floating over the map: `rgba(248,249,255,.7)` + `backdrop-blur(20px)` + hairline white border (nav bars, floating header pill, map overlays, sticky footers).
- **Motion:** 100ms micro / 250ms standard / 400ms panel; spring ease `cubic-bezier(.34,1.56,.64,1)`; press feedback `active:scale-95`; pulse-glow on active map markers; slide-in map sidebar; float animation on hero mockups; WebGL shimmer skeleton on `#f8f9ff`.

### 9.5 Signature components (from the 14-screen Stitch kit + implementation)

- Price-pill→**status-pill map markers** with caret + pulse glow when active; teardrop drop-pin with bounce; glass map search overlay; locate + zoom control stack.
- Spot card (photo header with glass trust badge, freshness line, metadata chips, contributor footer with avatar); spot detail panel (gallery, attribute chips, contributor snippet, verification timeline, sticky CTA footer; mobile bottom sheet with drag handle).
- 4-step spot creation wizard (progress bar, animated steps, success step with points celebration).
- Gamification set: XP progress bars (gradient), streak card (orange gradient + week-day discs), trust-score SVG ring, leaderboard podium with medals, achievement cards (locked = grayscale + lock), contribution heatmap, celebration banner (blue gradient + trophy).
- Soft status badges (`bg-{color}/10 + text-{color}` + icon), verification timeline with check nodes, notification cards with unread left-accent bar, moderation master-detail/tri-pane layouts, settings section cards with anchor nav.
- Navigation: desktop glass top-nav or 256px side-nav (active item = bold + right rail); mobile 5-tab bottom nav (Explore / Saved / List / Rankings / Profile) with active pill tab.

## 10. UX principles & constraints

- **Clarity first:** every screen answers "what can I do next: find, share, verify, claim, report, or review."
- **Real data over decoration:** freshness, verification state, distance, legal context, vehicle fit, trust — these ARE the content.
- **Trust is visible:** show why a spot is credible (photo, time, status, verifier count, contributor trust).
- **Privacy is understandable:** location and Smart Return choices explained in plain language, not buried in settings.
- **Calm density:** information-dense but never crowded; hierarchy + progressive disclosure.
- **Mobile first:** capture happens at the curb; primary actions thumb-accessible; flows short.
- **Accessibility:** body ≥16px, WCAG AA contrast, visible focus, large touch targets, labels for icons, reduced-motion respected, status never color-alone.
- **Gamification is a support feature, not the brand core** — no trophy/game iconography as primary identity.
- **i18n:** Turkish default + English; avoid text-in-images; allow for ~20–30% longer strings.

## 11. Tech stack (design-relevant summary)

- **Web:** React 19 + Vite 6 + TypeScript + Tailwind 3.4 (design tokens as CSS-variable RGB triplets so `/10`-style alpha works), React Router v6 with lazy routes and role guards, TanStack Query v5, zustand, react-hook-form + zod, sonner toasts, self-hosted Inter + Material Symbols fonts, PWA (manifest, service worker, offline page). Maps: **MapLibre GL JS** via react-map-gl — MapTiler vector tiles when a key is present, key-free OpenStreetMap raster fallback otherwise. Tests: Vitest/RTL/MSW + Playwright.
- **Mobile:** Expo ~56 / React Native 0.85 / Expo Router (file-based), light+dark theme system, expo-camera/image-picker/image-manipulator/location/notifications/secure-store, bottom-sheet + reanimated. Map = MapLibre GL inside a WebView with a postMessage bridge and GPU clustering. Offline-aware upload retry; wizard draft persistence.
- **Frontend rule:** business rules live on the backend; the frontend validates input shape and renders API results through a single gateway entrypoint.
- **Backend:** Java 21 / Spring Boot 3.5 microservices behind Spring Cloud Gateway — auth, user, parking (PostGIS), media (MinIO + ClamAV), gamification, notification, moderation, ai-validation (Gemini vision), analytics; the gateway also owns the public waitlist. Kafka events, Redis, Prometheus/Grafana/Loki observability, Docker Compose hosted-beta topology.
- **Auth:** RS256 JWT + JWKS, refresh rotation, HttpOnly cookies on web / SecureStore on mobile; RBAC (`USER / MODERATOR / ADMIN / SUPER_ADMIN`).

## 12. Landing page (parkio.dev) — specified structure

Hero ("Parking intelligence powered by real drivers." + honest status line + Join-waitlist CTA) → Problem → Solution loop → How it works (Find / Share / Verify / Return stepper) → Feature groups → Trust & privacy ("Built for trust, not surveillance.") → Technology credibility → Hosted beta expectations → FAQ (explicitly answers cost, location tracking, data deletion) → Footer with diligence links. CTAs stay honest and beta-oriented (no fake urgency). Implementation status: the landing was built and is deployed as a static site artifact (Hostinger) separate from the SPA; its React source was removed from the working tree, so a landing redesign would be fresh implementation work.

## 13. Roadmap (design planning horizon)

1. **Now:** hosted-beta deployment (operator-run, one geography).
2. **Beta cohort 1:** registration → upload → discover → verify/claim → report → notifications; Smart Return only after smoke tests.
3. **Beta cohort 2:** more testers / second geography, moderation capacity, mobile device coverage.
4. **Public beta → production hardening:** managed data plane, legal/privacy pages, deletion workflows, on-call.
5. **Product roadmap:** tune trust scoring with real data, validate Smart Return usefulness, premium/partner hypotheses only after measured usage.

## 14. Known design tensions & open questions (what the design prompt must resolve or respect)

1. **Money does not exist in this product.** The Stitch mockup kit (and parts of `DESIGN_SYSTEM.md` extracted from it) shows hourly prices, `$` price inputs, price-pill map markers, host ratings, "Book" buttons, and revenue analytics. The real product has zero payment/booking concepts — the shipped UI correctly uses status/verification markers and freshness/trust content instead. Any new design must replace "price" slots with **status + freshness + distance + vehicle-fit + trust**.
2. **Ephemerality is the defining product physics.** Spots live ~10 minutes, extended to 15–20 by verifications, and die on 2 "filled" reports. Design listings/detail around countdowns, "verified X min ago," and freshness decay — not permanent-listing aesthetics.
3. **Blue is canonical.** The shipped logo (blue rounded "P" with white car), app icons, Stitch V2 tokens, and both apps use the electric-blue system. The older teal color doc (`docs/brand/05-color-system.md`) is historical — don't resurrect teal unless deliberately rebranding.
4. **Two Stitch token generations:** v1 (indigo secondary; only login/register mockups) is deprecated; **V2 is canonical** (emerald secondary, amber tertiary).
5. **Gamification in mockups exceeds the backend.** Streaks, achievements/badges, and contribution heatmaps appear in mockups but are NOT modeled in the backend (points, levels with functional perks, trust score/bands, and leaderboard ARE). Either design without streaks/achievements or explicitly mark them as net-new scope. Brand rule: gamification is a support feature, never the brand core.
6. **Levels are functional, not cosmetic** — they literally widen search radius, result count, and daily views. This is a distinctive mechanic worth making visible ("level up to see further").
7. **No social login.** Mockups show a Google OAuth button; the product is email + password + mandatory email verification only.
8. **One photo per spot.** Multi-photo galleries in mockups are off-model. EXIF/GPS metadata is stripped server-side; photos are served via short-lived signed URLs.
9. **Turkish-default bilingual.** Mockups are English-only; the product defaults to Turkish (İzmir beta). Designs must tolerate ~20–30% longer strings, avoid text baked into images, and keep icon+label pairing.
10. **Dark mode scope:** web has none today; mobile has dark palettes. Decide whether the design covers dark mode now (calm, never neon/hacker) or stays light-first.
11. **Honest-stage marketing:** landing/marketing designs must carry the hosted-beta status truthfully — no fake social proof, user counts, or "launched" claims. CTAs are waitlist/documentation/contact, not sign-up-now-and-pay.
12. **Trust surfaces are first-class:** verification timelines, trust bands (UNTRUSTED→HIGH_TRUST), moderation states (pending review, suspicious, rejected), and appeals all need designed states — including the "your spot is pending AI validation / held for review" moments in the upload flow.
13. **Privacy is a visible feature:** location choices, Smart Return home location, and photo metadata stripping should be explained in-context, not buried.

## 15. How to use this document

This is the ground-truth project definition. When finalizing a design prompt from it: pick the target surface (product app screens, mobile, landing, or admin), carry over §8–§10 (brand, tokens, principles) as constraints, use §6–§7 for the real screens/flows and their states, and apply §14 to avoid designing off-model concepts (prices, streaks, OAuth, multi-photo).
