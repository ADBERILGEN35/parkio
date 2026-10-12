# U04 live pending-profile ownership acceptance: procedure

Asana U04 1219001664327469. Live acceptance on the deployed web image, with synthetic accounts only, inside a window
of `u05-priv001a-window.py --scenario u04`. The window reuses the PRIV-001A window's settings, guards, restore and
W0/W6 comparison:
- W1: invite-only registration, invite creation, account erasure; auth-service recreated alone;
- W2: a bounded hold (U04_HOLD, at most RESTORE_BY - 600 s after W1) for the browser acceptance;
- W4/W5: canonical erasure of every stored account, then the accounting;
- restore and W6, as for U05.

## What runs where

| Step | Where | What |
|---|---|---|
| stage + preflight | parkio-civo-prod, as civo with sudo | files fetched at a pinned commit, SHA-256 checked; the runner's read-only preflight |
| start | parkio-civo-prod | `sudo -n python3 -I <tools>/u05-priv001a-window.py start --harness <harness> --scenario u04` |
| browser acceptance | operator workstation | `node u04-live-ownership.mjs --run <RUN> --evidence <dir> --map-fingerprint <D4 fp>` |
| cleanup, restore, W6 | parkio-civo-prod, the window unit | automatic after `u04-done` (the tool sends it last) or at the hold's end |

The tool calls the window only through its operator commands, each over the existing Windows OpenSSH access as
`sudo -n python3 -I /root/u05-priv001a-window/<RUN>/window.py u04-<action> --run <RUN>`:

1. `u04-invite`: one invite token on stdout, held in the tool's memory only.
2. `u04-creds --name Rn` (stdin `email=`/`password=`): BEFORE the registration, so the cleanup can always find the account.
3. The browser registers through `https://app.parkio.dev/register?invite=…`.
4. `u04-verify --email …`: the harness's allowlisted verification, only for accounts registered since W1.
5. `u04-done` ends the hold.

## Accounts and cases

Three registrations, so that the verification emails to the reserved `.invalid` domain stay few. Each is accepted by
the provider and bounces.

| Account | Role |
|---|---|
| R1 | registers and signs in as itself (A→A), and is the "B" of the other cases |
| R2 | registers; R1 signs in in its tab (A→B); later verified and signs in after R1's sign-out (logout path) |
| R3 | registers; full reload; R1 signs in (reload path); never verified (the cleanup verifies it within the hour) |

The cases map to the U04 criteria:

| Criterion | Case |
|---|---|
| A→A PATCH 1 | `a-a` |
| A→B PATCH 0 | `a-b` |
| reload/logout do not transfer data | `logout-1`, `logout-2`, `reload-b` |
| no phone in storage | every case: localStorage, sessionStorage, IndexedDB, Cache Storage and cookies |
| map renders with a non-synthetic key | `map` |

## Before the window

- The tool's dry run against the production bundle with a mocked API: `node u04-live-ownership.mjs --mock-api …`.
  No request reaches api.parkio.dev, and no SSH call is made.
- The map criterion can be checked read-only at any time with `--map-only https://app.parkio.dev/explore`.
  On 2026-10-12 it FAILs: MapTiler answers "Invalid key" for the deployed key (fingerprint 192532e73577). This is
  Asana 1219427551506993. The ownership cases do not depend on the map.

## After the window

- `status --run <RUN>`: verdict PASS.
- `window-report.json` accounting:
  - `u04Accounts`: R1, R2 and R3 ERASED with their tombstone email and their request COMPLETE;
  - invites since W1: 3 created, 0 unconsumed;
  - no synthetic account left, and 0 other erasure requests.
- W6 comparison PASS: env byte-identical, the other containers unchanged.
- If the runner or the guards did not restore everything, follow `u05-priv001a-RECOVERY.md` (version 3 covers U04).

## Never

- Never print, store or post invite tokens, passwords, bearer tokens, cookie values or the map key.
- Never use a real person's account or data.
- Never take a screenshot of a page that shows a token.
