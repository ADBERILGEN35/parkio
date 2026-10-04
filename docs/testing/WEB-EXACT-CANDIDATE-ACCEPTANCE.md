# Web exact-candidate acceptance (U16, CX-F11)

**What this is:** browser acceptance of the web app's main flows, run against a built web image (the exact candidate) with every API call mocked and faults injected. It is test-only: no deploy, no real accounts, no e-mails or notifications.

**Measured on:** 2026-10-04.

| | |
|---|---|
| Candidate | `frontend/apps/web/Dockerfile` built from `06f4a6fc` with the release bake flags of `docker/web-hosted-beta.release-bake.env` (hosted-beta, public Explore and municipal discovery on) and a synthetic MapTiler key; image `sha256:4874fc3f34280e39e59c10e74feb1dc10ceedf02845a3030f7d014303a692090`, served by its own nginx on 127.0.0.1. Not pushed; removed after the run. |
| Harness | `frontend/apps/web/playwright.candidate.config.ts` with `CXF11_WEB_URL`; Playwright 1.60, Chromium |
| Network | Every `/api/v1` call is answered by the spec's mock (`acceptance/support.ts`). Faults are injected per call: a dropped connection, a delay, or HTTP 503. All other hosts, including map tiles and styles, are aborted. |
| Without an image | The same specs run on a Vite dev server built with the same flags: `pnpm --filter @parkio/web e2e:cxf11`. Frontend CI runs them that way on every frontend change. |

Real-provider checks are not part of this run; they stay separate. `frontend-real-e2e.yml` (dispatch only) registers and verifies a real account against a hosted or locally booted stack.

## Scenarios

The candidate run: **13 pass, plus 4 known-defect scenarios that fail as expected.** Each scenario saves screenshots to `test-results/cxf11/`.

| # | Scenario (task wording) | Test(s) | TR | EN | Notes |
|---|---|---|---|---|---|
| A | Register, verify, sign in, profile | `auth-flows`: register → check-email → verify → sign in → profile | pass | pass | Labels come from the app's own translations. One registration, one verification and one login request. |
| E1 | Retry after a dropped connection | `resilience`: sign-in on a dropped connection, then a retry | pass | pass | An alert is shown, the page stays on `/login`, and the retry signs in. |
| E2 | Duplicate submit creates one record | `resilience`: double click on *Create account*; double submit of the new-spot wizard (EN) | pass | pass | Exactly 1 `POST /auth/register`; 1 `POST /media/upload` and 1 `POST /parking/spots`. |
| E3 | Offline | `resilience`: the offline banner follows `context.setOffline` | pass | pass | |
| F | Admin RBAC | `admin`: a signed-in user without the admin role opens `/admin`, `/admin/users`, `/admin/waitlist` | pass | pass | The access-denied page is shown and no `/admin/*` API is called. The admin shell itself is `e2e/locale.spec.ts` Flow C, which passes on the candidate. |
| B1 | Explore with data | `explore`: public facilities load, no error state | pass | pass | |
| B2 | Explore query failure | `explore`: an error with a working retry | **known defect** | **known defect** | CL-F20 (Asana 1219002255930420). Today the query error removes the map and offers no retry. |
| B3 | Map style or tile failure | `explore`: an alert and a list fallback | **known defect** | **known defect** | CL-F20. Today a provider failure leaves an empty map. |
| C | Marketing waitlist error copy | `e2e/marketing-site.spec.ts` | | | CL-F19 (#212). Marketing files are deployed as they are in the repository, so the repository run is the candidate. |
| D | URL language | `e2e/auth-link-locale.spec.ts` | | | CL-F21 (#213). It runs once #213 has merged. |
| G | Keyboard and focus | `a11y/*` (CL-F30, #227); `e2e/web-muni-09-accessibility.spec.ts` | | | The keyboard walk on 23 pages in TR and EN is in #227's report. |

The known-defect tests use `test.fail`, set only after the page has rendered and the fault is in place, so a broken setup still fails the run. When CL-F20 lands, they report an unexpected pass and the marker must come off.

## The existing e2e specs on the candidate

All specs in `e2e/` except the marketing and service-worker ones ran against the image, on a desktop and a 360 px viewport. **Both viewports gave the same results.**

For comparison, the same specs ran on the default Vite dev server (`playwright.config.ts`) at the same app source (`e9d2d9bf`):
- desktop;
- 360 px (`galaxy-360`) for the specs the default config runs at that width;
- `wp03-routing` and `pending-profile-ownership` run on desktop only in the default config.

Frontend CI runs only `wp03-routing` and `pending-profile-ownership`; no workflow runs the other specs.

| Spec | Dev server | Candidate | Why |
|---|---|---|---|
| `mobile-layout`, `pending-profile-ownership`, `unsaved-changes`, `wp-spa-08`, `web-muni-01/-02/-03/-05/-06/-07`, `web-muni-09-flag-off-safety` | all pass | all pass | |
| `home` | 4 of 4 | 3 of 4 | *Legal pages reachable* fails on the image only. nginx redirects `/terms` and `/privacy` to the marketing site by design, so the SPA heading never shows. |
| `web-muni-08` | 4 of 4 | 3 of 4 | *Deep-link survives login* fails on the image only. It expects municipal URL parameters to be dropped, which holds only with municipal discovery off; the candidate has it on. With the flag on, the spec's catch-all mock also answers `GET /parking/roadside/nearby` with `{}` instead of a list, and the map page then fails to render. That is a mock artefact. |
| `wp03-routing` | 18 of 18 | 0 of 18 | Its request guard allows only `http://localhost:5193`, so the candidate origin is aborted (`net::ERR_FAILED`). |
| `y04a-product-analytics` | 2 of 4 | 1 of 4 | The spec needs a development server with municipal discovery on, which neither run provides. Two tests wait for `window.__PARKIO_ANALYTICS__`, which only development builds expose, so they fail on the image. Two wait for the municipal layer controls, which render only with the flag on, so they fail on the dev server. On the image, one of those two passes; the other then waits for the development hook. |
| `locale` | 4 of 5 | 4 of 5 | **Fails on both.** *Flow B* does not mock `/auth/registration-mode`, so the app shows *Kayıt şu anda kapalı* (registration closed), its fail-closed default. |
| `smoke` | 4 of 7 | 4 of 7 | **Fails on both: three stale tests.** *Register*: the same missing mode mock (*Registration is currently closed*). *Map search overlay*: it waits for a marker named *Active parking spot near 12 Curb Lane*. The app names it *Community parking spot near 12 Curb Lane, Active*, and that marker is present in both runs. *Claim*: it waits for *Claim this spot*, but the button has read *I parked here* since `b664928f`. |
| `web-muni-09-accessibility` | 0 of 6 | 0 of 6 | The spec refuses to run except on its own dedicated Vite server, by design. |

None of these failures is a product defect.
- **On the dev server as well**, apart from the by-design `web-muni-09-accessibility` refusal, six tests fail:
  - four are stale: smoke *register*, *map search overlay* and *claim*, and locale *Flow B*;
  - two are the y04a tests that need municipal discovery on.
- **On the image only**, the failures come from three things:
  - the deployment: nginx redirects, and municipal discovery is on;
  - wp03's pinned origin;
  - y04a's development hook.

Follow-up work:
- refresh smoke and locale for the current copy and the registration-mode gate;
- give wp03 a configurable origin;
- give y04a a server of its own;
- add the remaining specs to CI so they cannot go stale unnoticed.

## Not covered

- Native mobile apps: which one ships is undecided.
- A populated admin area beyond its shell.
- Real providers. Real map tiles, e-mail delivery and push are left to the real-stack workflow.

## Running it

```bash
cd frontend
pnpm --filter @parkio/web e2e:cxf11                            # dev server with the release flags
# against a built web image (see agent-tools/…/candidate-acceptance.sh):
CXF11_WEB_URL=http://localhost:18081 pnpm --filter @parkio/web exec playwright test -c playwright.candidate.config.ts
```
