# Browser-measured accessibility: web app and marketing site (U16, CL-F30)

**What this is:** a measurement in real Chromium, per page and in Turkish and English, plus the fixes for the WCAG failures it confirmed. **It is not a WCAG AA conformance claim** for the product: see "Not measured".

**Measured on:** 2026-10-04. The harness runs in Frontend CI (`pnpm --filter @parkio/web e2e:a11y`), so every PR repeats it.

| Target | What was measured | Result |
|---|---|---|
| Exact web candidate | `frontend/apps/web/Dockerfile` built from `1a39112b` (tree `67404b97`) with the release bake flags of `docker/web-hosted-beta.release-bake.env` and a synthetic MapTiler key; image `sha256:5657119042c1785fe0a9ffaee01715c91ae5033eefde8dbb2bed238df759c8ae`, served by its own nginx on 127.0.0.1. Not pushed; removed after the run. | 34/34 page checks pass |
| Web app on the Vite dev server | same pages and checks | 34/34 |
| Marketing site | `web/marketing` as `scripts/serve-marketing-site.mjs` serves it, with the `.htaccess` security headers (CSP `script-src 'self'`) | 12/12 |

Later commits change only the measurement harness and this document, not the web bundle.

## Method

- **Browser and engine:** Playwright 1.60 with Chromium, desktop viewport.
  - axe-core 4.10.2, resolved through `jest-axe`, so the unit tests and the browser use the same engine.
  - axe runs with the WCAG 2.0, 2.1 and 2.2 **A and AA** tags only.
  - Best-practice rules are not part of the pass criterion. A missing `main` landmark is recorded but not failed.
- **Per page and locale:**
  - `<html lang>` must match the language of the content: the selected locale on translated pages, and the page's own language on single-language pages.
  - Keyboard walk with Tab: every stop needs a visible focus indicator. The focused element must look different from its unfocused self in a way a user can see. That means either:
    - an outline that is visible (a style, a width and a colour that is not transparent) and differs from the unfocused outline; or
    - a box-shadow, border, background or underline that differs.

    Shadows are compared by their visible layers only. The style is read after the element's transitions finish (capped at 1 s). So a ring that fades in counts, while Tailwind's `focus:outline-none` (a 2px transparent outline) or a ring transition that has not started does not. A static decorative shadow does not count. Two synthetic-page tests pin the rule (#229 review N1). Transparency is read from both colour syntaxes, `rgba(…, 0)` and the slash form of newer functions such as `oklch(… / 0)` (#242 review N3).
  - Focus must leave the page or cycle back to the first stop; anything else is a trap.
  - Marketing footer: the pages built with a site footer (`/`, `/privacy/`, `/terms/`) must expose it as a `contentinfo` landmark, and every footer link has a name.
- **Network:** every API call is mocked. Requests to any other host are aborted, so nothing leaves the machine.
- **Locale:** the first-visit locale is set through local storage (`parkio.locale`, `parkio.marketing.locale`).

## Pages

| Web page | Signed in | TR | EN | Notes |
|---|---|---|---|---|
| `/login` | no | pass | pass | |
| `/register` | no | pass | pass | |
| `/forgot-password` | no | pass | pass | |
| `/reset-password?token=…` | no | pass | pass | |
| `/check-email` | no | pass | pass | |
| `/verify-email?token=…` | no | pass | pass | The success toast is measured with all four toast palettes. Focus cycles while the toast is open (see Observations). |
| `/terms` | no | pass | pass | Reached by in-app navigation. A direct request is redirected by nginx to the marketing site, measured below. |
| `/privacy` | no | pass | pass | As `/terms`. |
| `/explore` | no | pass | pass | Measured with the public-explore flag on (map and two synthetic car parks) since the #229 follow-up, as in the release images. The two synthetic car parks lie within the query's 5 km of the default origin, as the API returns them. One known `target-size` entry, the release image's attribution text link (see Known issues). No `main` landmark (best practice; see Observations). |
| `/map` | yes | pass | pass | No `h1` (best practice). |
| `/upload` | yes | pass | pass | |
| `/profile` | yes | pass | pass | |
| `/my-spots` | yes | pass | pass | |
| `/notifications` | yes | pass | pass | |
| `/gamification` | yes | pass | pass | |
| `/leaderboard` | yes | pass | pass | |
| `/reports` | yes | pass | pass | |

| Marketing page | TR | EN | Notes |
|---|---|---|---|
| `/` | pass | pass | `lang` follows the locale |
| `/privacy/` | pass | pass | English page (`lang="en"`); its Turkish parts are marked `lang="tr"` |
| `/terms/` | pass | pass | English page |
| `/waitlist/confirm/` | pass | pass | |
| `/waitlist/unsubscribe/` | pass | pass | |
| `/404.html` | pass | pass | English page |

## Confirmed failures, fixed in this change

| # | WCAG | Where | Measured | Fix | After |
|---|---|---|---|---|---|
| 1 | 1.4.3 Contrast (AA) | Web toasts (sonner `richColors`, light theme) | Text on its own background: success 4.29, info 4.35, warning 3.07, error 4.36 (13 px) | `AppToaster` sets sonner's four text variables to the app's secondary, primary, tertiary and error colours | 6.14, 6.50, 6.76, 5.84 |
| 2 | 1.4.3 | Marketing section kickers (`.kicker`, 12 px bold) | #0769fd on #f6f8fb: 4.43 | `--primary-2` | 7.31 |
| 3 | 1.4.3 | Marketing roadmap stage badges (11 px bold) | #0769fd on #eaf2ff: 4.19 | `--primary-2` | 6.91 |
| 4 | 1.4.3 | **Marketing footer** text (`.footer-bottom`, 12 px) | #8490a3 on #fff: 3.23 | `--muted` | 5.01 |
| 5 | 3.1.1 Language of Page (A), 3.1.2 Language of Parts (AA) | Marketing `/privacy/` | `lang="tr"` on an English policy | `lang="en"`; the Turkish title, tagline, home link and waitlist paragraph carry `lang="tr"` | language matches the content |
| 6 | 1.3.1 Info and Relationships (A) | Web `/my-spots` with spots (found once populated) | The `<ul>` held the spot cards directly, without `<li>` (axe `list`) | Each card is wrapped in `<li>`, as Explore's results already were | list semantics restored |
| 7 | 1.4.1 Use of Color (A) | Web `/gamification` with point history (found once populated) | The *view spot* link inside a line of text was distinguished only by colour (axe `link-in-text-block`) | The link is always underlined | distinguishable without colour |

Regression checks:
- 1: a unit test on the Toaster's variables, which fails without the fix; the browser palette measurement of all four types; axe on the real success toast.
- 2–4: axe colour-contrast on every marketing page.
- 5: the `lang` check, plus an assertion on the Turkish paragraph.
- 6: a unit test (each spot is a list item), which fails without the fix; axe on the populated page.
- 7: axe on the populated page.

## Observations (not WCAG A/AA failures)

- **`/explore` has no `main` landmark, and `/map` has no `h1`.** Both pages have headings or landmarks, so WCAG 2.4.1 (bypass blocks) is met. The missing pieces are best practice; they are left for a design decision.
- **Focus cycles on `/verify-email` while a toast is open.** When focus leaves the toaster, sonner returns it to the element that had it before. Tab therefore cycles through the page's three controls (Sign in, the toast, Close) instead of reaching the browser's own controls.
  - Every control stays reachable, so this is not a WCAG 2.1.2 trap.
  - It is recorded; the walk asserts that the cycle returns to the first stop.
- **Undecided results ("incomplete"), listed for manual review.** For these nodes axe could not decide, for example contrast over a gradient or an image. They are not counted as passes. See the table below.

| Page(s) | axe rule | Nodes (per locale) | Why axe could not decide | What to check by hand |
|---|---|---|---|---|
| Web `/login`, `/register`, `/forgot-password`, `/reset-password`, `/check-email`, `/verify-email`, and the app's `/terms`, `/privacy` | color-contrast | 3–6 | Text over the auth hero's background gradient (3 nodes, e.g. `.text-headline-md`, `.text-title-lg`). On some pages also one node overlapped by another element. | Headline and title contrast at the gradient's lightest point |
| Web `/map` | color-contrast | 11 | MapLibre attribution and controls over the map canvas: an image node, or overlapped | Attribution text over map tiles (tiles were blocked in this run) |
| Web `/reports` | color-contrast | 1 | A `textarea` partly obscured by another element | Placeholder and text contrast of the report textarea |
| Marketing `/` | color-contrast | 28 | Text whose background comes from a pseudo element (hero eyebrow, hero heading parts and similar) | Hero text over its decorative background |
| Marketing `/` | aria-prohibited-attr | 1 | `aria-label` on a `div` with no role (`.hero-proof`); assistive technology ignores it | Give the block a role (for example `group`), or drop the label if the text inside already says it |
| Marketing `/privacy/`, `/terms/` | link-in-text-block | 1–2 | `mailto:` links inside paragraphs; contrast blocked by element overlap | That the links stand out from the text by more than colour (they are bold) |

The undecided set is the same in Turkish and English. On the built web image the per-page counts match, except `/map` (12 nodes instead of 11).

## Not measured

- No screen-reader session (NVDA, VoiceOver, TalkBack).
- No zoom or reflow at 400 % (1.4.10), no text-spacing override (1.4.12), no mobile viewports.
- **Signed-in pages are measured in a populated state**, since the follow-up to #227. Since the #229 follow-up, each populated page must also show one synthetic value from its mocks (for example the profile's display name, the first notification's title, a leaderboard score). `/explore` was measured in its flag-off "unavailable" state until then; the dev server now turns the flag on. Profile, stats, preferences, notifications, nearby spots, my spots, gamification (progress, points, level, access policy, levels, leaderboard), reports and Explore facilities all answer with synthetic data. A page that makes an API call without such a mock fails the suite, so an empty or error state can no longer pass as the measured page. The first #227 runs measured the empty or error states only. Not every page has such a value: `/upload` has no list, and `/map` lists spots only after a location search, which the run does not make, so `/map` is measured in its state before a search. Gamification is checked on the access policy's search radius, a value only that call returns (#242 review N2).
- Interaction states are covered only partly: one toast state, the map without tiles and without an active parking session. Dialogs, the error states of each form and map interactions were not walked.
- Dark theme: the web app has none yet. If one is added, the toast override must become theme-aware: the app's light-theme text colours on sonner's dark backgrounds are only about 2.4–2.9:1.
- The mobile apps.
- Marketing pages in a staged configuration: only the launch configuration (waitlist mode `api`) was measured, not `mock` or `hidden`/`unavailable`.

## Running it

```bash
cd frontend
pnpm --filter @parkio/web exec playwright install chromium   # once
pnpm --filter @parkio/web e2e:a11y                           # Vite dev server + marketing server
# against a built web image instead of the dev server:
A11Y_WEB_URL=http://localhost:18080 pnpm --filter @parkio/web exec playwright test -c playwright.a11y.config.ts --project a11y-web
```

Per-page JSON results land in `frontend/apps/web/test-results/a11y/`. A measured violation that is documented rather than fixed goes into `frontend/apps/web/a11y/known-issues.ts` with its reason. An entry matches only the exact node it documents: the whole axe selector, a piece of its HTML and, where given, the failing check's message key, size and related node. Another node still fails the run. A worse failure of the same node fails it only where the entry pins that size: the MapLibre attribution link entry pins its height (14 px) but not its width, which follows the font (#247 review N1). After each run, `test-results/a11y/a11y-web-known-issues.json` lists how often each entry was seen, and the run log names the entries no page matched (#242 review N1).

## Known issues (documented, not fixed)

| Page | Rule | Where | What |
|---|---|---|---|
| `/explore` (release image) | `target-size` | `a[href$="maplibre.org/"]` | With the release bake and a MapTiler key, the attribution line can show a 14 px high "MapLibre" text link. WCAG 2.5.8 exempts targets inline in a line of text, and the line carries the map data credits MapTiler and OpenStreetMap require, but axe measures the link. The dev server, which has no MapTiler key, does not report it. |

### Fixed (Asana 1219147334320125)

Two further `target-size` issues on `/explore`, found once it was measured with the public-explore flag on, are fixed. Each fails again if its change is reverted.

- **Attribution toggle under the zoom rail.** At desktop width, MapLibre's attribution toggle sat under the floating zoom rail's zoom-out button, so only 24x6 px of it could be clicked. When the rail is at the map's right edge on desktop, it now sits 3rem up (`md:bottom-12`): `/explore`, and `/map` with its results sidebar closed. With the sidebar open, and on phones, it has not moved.
- **Overlapping markers.** This came from the measurement's mocks, not the product. The synthetic car parks were in Istanbul, 330 km from the explore query's İzmir origin, so the map framed both cities and the two markers covered each other. They are now within the query's 5 km, about 3 km apart.

Real car parks that are only a few metres apart can still overlap at the frame's maximum zoom. Clustering them would be a product decision; it is not part of this fix.
