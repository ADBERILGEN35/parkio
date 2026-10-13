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
3. The browser opens `https://app.parkio.dev/register` and passes `?invite=…` by in-app navigation (pushState). The
   page reads the token and removes it from the address at once, so the token is never in a request URL: not in the
   web container's nginx log (`$request`), Caddy's or Loki. It travels only in the registration request body, is
   single-use, and is consumed seconds later; an unused one is an ORPHAN and is inert once the mode returns to CLOSED.
4. `u04-verify --email …`: the harness's allowlisted verification, only for accounts registered since W1.
5. `u04-done` ends the hold.

## Accounts and cases

Three registrations. No verification email is sent: the deployed auth-service (ResendEmailSender, 504b9cb4) skips every
address of the reserved form `priv001a-<a-z0-9>@priv001a.parkio.invalid` and only logs it.

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

## U03 in the same window

U03 (Asana 1219001664236540, live privileged-token revocation) runs on the host, inside the same hold, BEFORE the
browser tool, because the browser tool ends the hold:

```
ssh … "sudo -n python3 -I /root/u05-priv001a-window/<RUN>/window.py u03-acceptance --run <RUN>"
```

It takes about two minutes:

1. Accounts X and Y are created through the reviewed harness: invite, registration, allowlisted verification.
2. X is given ADMIN by one allowlisted SQL statement. It applies only to a synthetic ACTIVE account registered since W1 that holds only USER and has no erasure request. The statement adds the role row and bumps the session epoch, as grantRole does.
3. Controls that request no real user's data, with X's new admin token:
   - `/admin/users?userId=X` must return only X;
   - the waitlist export over a 2099 window;
   - a suspend on a random, nonexistent id, which must give 404.
4. X loses ADMIN through the matching statement, as revokeRole does.
5. The old admin token must be refused on read, export and mutation within the gateway's epoch cache TTL (PT30S, unset live).
6. X's new token must carry USER only.
7. Y's token must keep working, with Y's epoch unchanged.
8. Missing, malformed and re-signed JWTs must be refused.

Tokens never leave the host. The output is `u03/u03-results.json` in the run's evidence directory, plus `u04/u03.log`.

If the command fails or is cut off, its `finally` step removes ADMIN. The window's cleanup also removes ADMIN from every id in `elevated.log` before erasing it, and the accounting refuses to pass while any id there still holds ADMIN.

The criterion "epoch lookup error does not grant access" is not reproduced live, because that would need a production fault. Its evidence is isolated fault injection on the running images: the 22-case auth+gateway acceptance of candidate run 38087836817 (source 504b9cb4). Its fail-closed cases cover a null, malformed, mismatched, empty or missing epoch and a restored proxy, and the deployed auth/gateway config digests equal that run's accepted image ids.

Start the tool promptly once the hold begins: the hold closes at U04_HOLD (or earlier, at RESTORE_BY - 600 s), and
from then on every operator command is refused. The erasure flag is on for every user during the hold, so keep it
short; a real user's erasure in that time is counted (`otherErasureRequestsSinceW1`) and must be recorded and explained.

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
