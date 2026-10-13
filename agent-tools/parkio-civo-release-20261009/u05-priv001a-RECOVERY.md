# U05 PRIV-001A window: independent recovery procedure (version 4: also the U04 scenario and U03 in it)

## When to use it

Use this only when the window runner (`u05-priv001a-window.py`) **and** its guards did not restore everything. For example:
- the runner reported `CONFLICT`, `RESTORE INCOMPLETE` or a FAILED restore step;
- the runner and the guards both failed.

## What it needs

It does not use the runner's code. It needs only:
- the production wrapper;
- `docker`, `systemctl`, `sha256sum`, `stat`, `sed`, `cp` and `shred`;
- the verified copies that the runner left in the run directory: the harness and the snapshot tool.

Run every command on **parkio-civo-prod** as civo with sudo, in one SSH session. Never print the env file, and never print the operator token's line.

## Notation

Set these once at the start of the session:

```
RUN=$(sudo ls /root/u05-priv001a-window | grep -E '^[0-9]{8}T[0-9]{6}Z$' | tail -1); echo "$RUN"
S=/root/u05-priv001a-window/$RUN                        # root-only: state.json, env.backup, creds, harness/, window.log
EV=/home/civo/parkio-release-20261009/u05-window/$RUN   # evidence: W0/W6 snapshots, reports
ENV=$(sudo readlink -f /opt/parkio/docker/.env.azure-hosted-beta)    # the real file, as the runner resolves it
sudo stat -c '%F %a %u:%g' "$ENV"; sudo grep -c $'\r' "$ENV" || true   # expected: regular file; 0 CRLF lines (2026-10-12)
W() { (cd /opt/parkio-release && sudo PARKIO_ENV_FILE="$ENV" ./scripts/parkio-prod-compose.sh "$@"); }
C() { sudo docker ps --filter label=com.docker.compose.project=parkio --filter "label=com.docker.compose.service=$1" --format '{{.Names}}'; }
Q() { sudo docker exec -e "PGOPTIONS=-c default_transaction_read_only=on" "$(C postgres-auth)" sh -c \
      'exec psql -X -q -At -F "|" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"' psql "$1"; }   # read-only queries
F() { sudo python3 -I -c 'import json,sys; s=json.load(open(sys.argv[1])); print(s.get(sys.argv[2]))' "$S/state.json" "$1"; }
```

`F fault` tells you which services the window touched:
- `none`: only auth-service.
- `analytics`: auth-service and analytics-service.

`F scenario` tells you which accounts the window created:
- `u05` (or `None`, a version-2 run): one principal, credentials in `$S/creds`.
- `u04`: browser-registered accounts, one credentials file each in `$S/u04/creds-<name>`, and one line per created
  invite in `$S/u04/invites.log`. Wherever a step below names `$S/creds`, repeat it for each of these files.

## 1. Stop everything still running from the window

```
sudo systemctl list-units --all --plain --no-legend 'u05-priv001a-*'
sudo systemctl kill --signal=SIGKILL "u05-priv001a-window-$RUN.service"            # only if listed as active
sudo systemctl kill --signal=SIGKILL "u05-priv001a-guard-window-$RUN.service" \
                                     "u05-priv001a-guard-analytics-$RUN.service"   # only if listed as active
sudo systemctl stop "u05-priv001a-guard-window-$RUN.timer" "u05-priv001a-guard-analytics-$RUN.timer"
sudo systemctl list-units --all --plain --no-legend 'u05-priv001a-*'                # expected: nothing active
```

Kill a guard **service** that is already running before you stop the timers. Stopping a timer does not stop a guard that has fired.

## 2. analytics-service

```
sudo docker ps --filter label=com.docker.compose.service=analytics-service --format '{{.Names}} {{.Status}}'
W start analytics-service           # only if it is not running
```

- **Use `start`, never `up`.** While the env file still holds the window values, `up` would recreate the container.
- **Wait for `(healthy)`.**

## 3. Accounting (read-only), then the synthetic principal

Every account and invite the window created is counted from W1:

```
W1=$(F w1); echo "$W1"
Q "select id, status, email from auth_users where email like 'priv001a-%@priv001a.parkio.invalid' and created_at >= '$W1'::timestamptz"
Q "select count(*), count(*) filter (where consumed_at is null) from registration_invites where created_by = 'priv001a' and created_at >= '$W1'::timestamptz"
```

- **No row from the first query:** no synthetic account is left. An erased account keeps only its tombstone email, so it no longer matches.
- **U04:** a listed account's credentials file is the one holding its (synthetic) email:
  `sudo grep -l "^email=<email>$" "$S"/u04/creds-*`.
- **U04:** the second query must show as many created invites as `$S/u04/invites.log` has lines
  (`sudo wc -l < "$S/u04/invites.log"`). A listed account with no credentials file is an ORPHAN (its registration was
  not preceded by `u04-creds`): record it, never change it by SQL.
- **U03 (when `$S/u04/elevated.log` exists):** each id listed there was given ADMIN for the U03 acceptance. Before you
  erase it, read its roles: `Q "select r.name from auth_user_roles ur join roles r on r.id = ur.role_id where ur.user_id = '<id>'::uuid"`.
  If ADMIN is listed, remove it with the runner's own statement. It runs in a read-write session, deletes only that
  id's ADMIN row and bumps its session epoch, as revokeRole does. Expect exactly one row back:

  ```
  sudo docker exec "$(C postgres-auth)" sh -c 'exec psql -X -q -At --set=ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"' psql \
    "with del as (delete from auth_user_roles ur using roles r, auth_users u where ur.role_id = r.id and r.name = 'ADMIN' and ur.user_id = '<id>'::uuid and u.id = ur.user_id and (u.email like 'priv001a-%@priv001a.parkio.invalid' or u.email = 'erased-' || u.id::text || '@invalid.localhost') returning ur.user_id) update auth_users a set session_epoch = a.session_epoch + 1, version = a.version + 1, updated_at = now() from del where a.id = del.user_id returning a.id, a.session_epoch"
  ```

  Never change the roles of an id that is not listed in `elevated.log`.
- **An unconsumed invite:** record it as an ORPHAN. Only the invite's hash is stored, so no one can use it, and it expires after the invite TTL (P7D).

**For each account the first query lists:**

1. Look for its erasure request:

   ```
   Q "select status, requested_at from erasure_requests where auth_user_id = '<id>'::uuid order by requested_at desc limit 1"
   ```

2. **If a request exists,** do not send another DELETE. Wait for it to reach COMPLETE: analytics-service is running after step 2. Observe it with the harness, read-only:

   ```
   sudo bash "$S/harness/scripts/acceptance/erase-priv001-synthetic-principal.sh" --environment civo-production \
     --confirm-synthetic-only --credentials-file "$S/creds" --expect COMPLETE --timeout-seconds 600
   ```

3. **If no request exists,** erase the account canonically **before** step 4 turns the flag off.

   First check the conditions:
   - the running auth-service must still have the flag on: `sudo docker exec "$(C auth-service)" printenv PARKIO_ACCOUNT_ERASURE_ENABLED` prints `true`;
   - the harness copy must match the pins below: `sudo sha256sum "$S"/harness/scripts/acceptance/*.sh "$S"/harness/scripts/lib/*.sh`.

   Then erase:

   ```
   sudo bash "$S/harness/scripts/acceptance/erase-priv001-synthetic-principal.sh" --environment civo-production \
     --confirm-synthetic-only --credentials-file "$S/creds" --delete --expect COMPLETE --timeout-seconds 600
   ```

   The harness binds the DELETE to the file's synthetic account. A PENDING_VERIFICATION account is verified first, which works only within an hour of its registration. Exit 4 means the file's account was never registered.

4. **If it cannot be erased,** record the account id as an ORPHAN for the next authorized window. Never delete or change its rows by SQL.

Harness pins: the SHA-256 at the PR #338 merge commit `9ffdd377c188ebd28babd54089b5049d71610435`, the same values as
`HARNESS_PINS` in the runner. `sha256sum` prints them in this order:

```
5d82417fe723ab9732fb8e4a8f1382ae9cb8885c9c1c6dc3330134c8a87731c6  scripts/acceptance/create-priv001-synthetic-principal.sh
7b7e93657e2024ab8924b18cce65b934aafc2f82b17fec83af8cf0ec6f7f96ad  scripts/acceptance/erase-priv001-synthetic-principal.sh
b04e23c5a717b1bd6cdf43344700afa7b5be145620bfea33135f98e5f37fcdae  scripts/acceptance/inspect-priv001-synthetic-residue.sh
3f4fee89cec02c1c45cc2457d56f86b92dbc9d963b5428024da820e77db9896d  scripts/lib/priv001-synthetic.sh
f58eebaa7afd358fd90c681aec3968af66013cbcbf2d75445f7d63739be36b13  scripts/lib/dark-gateway-url.sh
```

## 4. The env file: only the window keys

**4a. Compare the file with the two hashes the runner recorded:**

```
sudo sha256sum "$ENV"; echo "W0 $(F envSha0)"; echo "W1 $(F envSha1)"; F changedKeys
```

**4b. The hash equals W0.** The file is already restored. Go to step 5.

**4c. The hash equals W1.** The file is exactly what W1 wrote. Restore the backup byte for byte. Use plain `cp`, not `cp -p`, so the live file keeps its mode and owner:

```
sudo cp "$S/env.backup" "$ENV" && sudo sha256sum "$ENV"      # must equal W0
sudo stat -c '%a %u:%g' "$ENV"; F envStat                     # must equal W0's mode and owner
```

**4d. The hash equals neither.** Someone else changed the file during the window. Do **not** copy the backup over it. Revert only the window's own lines.

1. Print what the window changed. The token line is listed only when its W0 value was empty:

   ```
   sudo python3 -I -c 'import json,sys; [print(p["key"], "appended" if p["appended"] else "replaced", repr(p["original"])) for p in json.load(open(sys.argv[1]))["patches"]]' "$S/state.json"
   ```

2. For each key, apply the exact line edit:

   | Window change | Command |
   |---|---|
   | `PARKIO_ACCOUNT_ERASURE_ENABLED`, appended | `sudo sed -i '/^PARKIO_ACCOUNT_ERASURE_ENABLED=true$/d' "$ENV"` |
   | `PARKIO_ACCOUNT_ERASURE_ENABLED`, replaced | `sudo sed -i 's/^PARKIO_ACCOUNT_ERASURE_ENABLED=true$/<original line>/' "$ENV"` |
   | `PARKIO_REGISTRATION_INVITE_CREATION_ENABLED`, replaced | `sudo sed -i 's/^PARKIO_REGISTRATION_INVITE_CREATION_ENABLED=true$/<original line>/' "$ENV"` |
   | `PARKIO_REGISTRATION_MODE`, replaced (only if listed) | `sudo sed -i 's/^PARKIO_REGISTRATION_MODE=invite$/<original line>/' "$ENV"` |
   | `PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN`, appended (only if listed) | `sudo sed -i '/^PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN=/d' "$ENV"`. The runner created that line. |
   | `PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN`, replaced (only if listed) | Put the empty original line back: `sudo sed -i 's/^PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN=.*$/<original line>/' "$ENV"` |

3. Check the result. `sudo grep -c '^PARKIO_ACCOUNT_ERASURE_ENABLED=true$' "$ENV"` must print 0 (and the same check applies to the other window values), and `sudo stat -c '%a %u:%g' "$ENV"` must show W0's mode and owner.

The `sed` patterns assume LF line endings, which the host had on 2026-10-12. If the CRLF count at the top is not 0, stop and decide with the owner: these patterns would not match.

- **If a window key now holds a value that neither W0 nor the window wrote,** stop. That is a concurrent owner change; decide with the owner.
- **The other change stays** in the file. Record it with whoever made it.

## 5. auth-service with the restored settings

Do this only after step 4:

```
W up -d --no-deps --no-build auth-service
sudo docker ps --filter label=com.docker.compose.service=auth-service --format '{{.Names}} {{.Status}}'   # wait for (healthy)
sudo docker exec "$(C auth-service)" printenv PARKIO_ACCOUNT_ERASURE_ENABLED PARKIO_REGISTRATION_MODE PARKIO_REGISTRATION_INVITE_CREATION_ENABLED
```

Never print the operator token variable.

## 6. Verify

Use the verified snapshot copy in `$S`, with the run's evidence directory:

```
CH=auth-service; [ "$(F fault)" = analytics ] && CH=auth-service,analytics-service
sudo U05W_OUT="$EV" U05W_CHANGED="$CH" python3 -I "$S/u05-window-snapshot.py" snapshot W6
sudo U05W_OUT="$EV" U05W_CHANGED="$CH" python3 -I "$S/u05-window-snapshot.py" compare
```

- **Expected:** `U05 WINDOW COMPARE PASS`.
- **After 4d,** the comparison also reports `the env file's SHA-256 differs from W0`. That is expected; record it.
- **Any other DIFF** is a real difference and must be reported.

Then repeat the two accounting queries of step 3. The expected result:
- no listed account;
- the invites since W1 show `1|0` (U04: `<lines of invites.log>|0`): every invite created, none unconsumed;
- U03: no id in `$S/u04/elevated.log` holds ADMIN.

## 7. Clean up

**After a verified restore with no open erasure:**

```
sudo shred -u "$S/env.backup" "$S/creds"                   # U04: "$S/env.backup" "$S"/u04/creds-*
sudo find "$S/tmp" -type f -exec shred -u {} + ; sudo rm -rf "$S/tmp"
# An interrupted write leaves "..env.azure-hosted-beta.u05-<pid>" next to the env file; it holds the whole env.
sudo find "$(dirname "$ENV")" -maxdepth 1 -name ".$(basename "$ENV").u05-*" -print -exec shred -u {} +
```

- **Keep `creds`** (U04: every `u04/creds-*`) while an erasure is still open (step 3).
- **The rest of `$S`** holds no secret value and stays as evidence: `state.json`, `window.log` and the verified copies.

**Report:** the steps taken, the outputs of step 6, and every ORPHAN or PENDING item.
