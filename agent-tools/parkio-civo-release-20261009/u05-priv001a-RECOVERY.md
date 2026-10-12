# U05 PRIV-001A window: independent recovery procedure

Use this only when the window runner (`u05-priv001a-window.py`) **and** its guards did not restore everything. For example:
- the runner and the guards both failed;
- the runner reported CONFLICT or "RESTORE FAILED".

It needs only:
- the production wrapper and `docker` (for the read-only checks);
- `sha256sum`, `cp` and a text editor;
- the snapshot tool.

It does not use the runner's code. Run every command on **parkio-civo-prod** as civo with sudo, in an SSH session.

Notation:
- **`RUN`:** the newest directory under `/root/u05-priv001a-window/` (`sudo ls /root/u05-priv001a-window`).
- **`S`:** `/root/u05-priv001a-window/$RUN`. It is root-only and holds `state.json`, `env.backup` and `creds`.
- **`ENV`:** `/opt/parkio/docker/.env.azure-hosted-beta`.
- **`W`:** `cd /opt/parkio-release && sudo PARKIO_ENV_FILE=$ENV ./scripts/parkio-prod-compose.sh`.

## 1. Stop anything still running from the window

```
sudo systemctl list-units --all --plain --no-legend 'u05-priv001a-*'
sudo systemctl kill --signal=SIGKILL "u05-priv001a-window-$RUN.service"   # if it is listed as running
sudo systemctl stop u05-priv001a-guard-analytics.timer u05-priv001a-guard-window.timer
```

## 2. analytics-service first (its approved stop limit is 10 minutes)

```
cd /opt/parkio-release && sudo PARKIO_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta ./scripts/parkio-prod-compose.sh start analytics-service
```

Use `start`, never `up`. While the env file still holds the window values, `up` would recreate the container.

## 3. The synthetic principal, if it was never deleted

**Check the state:**
- `sudo python3 -I -c 'import json,sys; s=json.load(open(sys.argv[1])); print(s.get("authUserId"), s.get("deleteSent"))' "$S/state.json"`.

**If `authUserId` is set and `deleteSent` is not `true`:** erase the principal canonically **before** the flag is turned off (step 4). The flag is still on at this point. Use the harness at the reviewed commit:

```
sudo bash <harness>/scripts/acceptance/erase-priv001-synthetic-principal.sh --environment civo-production \
  --confirm-synthetic-only --credentials-file "$S/creds" --delete --expect COMPLETE --timeout-seconds 900
```

**If it cannot be erased,** record the principal as an orphan (its `authUserId`) for the next authorized window. Never delete its rows by SQL.

## 4. The env file: only the window keys

**4a. Compare the file with the two hashes the runner recorded:**

```
sudo sha256sum /opt/parkio/docker/.env.azure-hosted-beta
sudo python3 -I -c 'import json,sys; s=json.load(open(sys.argv[1])); print("W0", s["envSha0"]); print("W1", s["envSha1"]); print("keys", s["changedKeys"])' "$S/state.json"
```

**4b. The file is exactly what W1 wrote** (its hash equals `W1`). Restore the W1 backup byte for byte:

```
sudo cp -p "$S/env.backup" /opt/parkio/docker/.env.azure-hosted-beta && sudo sha256sum /opt/parkio/docker/.env.azure-hosted-beta
```

The new hash must equal `W0`.

**4c. The hash equals neither.** Someone else changed the file during the window. Do **not** copy the backup over it. Edit only the keys listed in `changedKeys`:

| Key | What to do |
|---|---|
| `PARKIO_ACCOUNT_ERASURE_ENABLED` | Put the W0 line back. It is in `state.json` → `patches[].original`, or the line is removed if `appended` is true. |
| `PARKIO_REGISTRATION_INVITE_CREATION_ENABLED` | The same. |
| `PARKIO_REGISTRATION_MODE` | Back to its W0 line, but only if it is in `changedKeys`. |
| `PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN` | Only if it is in `changedKeys`: the runner created it, so remove that line (`appended` true). If it replaced an empty line, put back an empty value. |

- **If one of these keys now holds a value that neither W0 nor the runner wrote,** stop. That is a concurrent owner change; decide with the owner.
- **Never print the operator token line.** When you edit, delete the line without displaying it, for example: `sudo sed -i '/^PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN=/d' <file>`. Use this only when the runner appended the line.

## 5. auth-service with the restored settings

```
cd /opt/parkio-release && sudo PARKIO_ENV_FILE=/opt/parkio/docker/.env.azure-hosted-beta ./scripts/parkio-prod-compose.sh up -d --no-deps auth-service
```

Then wait for `healthy`: `sudo docker ps --filter label=com.docker.compose.service=auth-service --format '{{.Names}} {{.Status}}'`.

## 6. Verify

```
sudo python3 -I /home/civo/parkio-release-20261009/tools/u05-window-snapshot.py snapshot W6
sudo python3 -I /home/civo/parkio-release-20261009/tools/u05-window-snapshot.py compare
```

- **Expected:** `U05 WINDOW COMPARE PASS`.
- **If the env file kept a concurrent change (4c),** the comparison reports `the env file's SHA-256 differs from W0`. That is expected; record it with the change's owner.
- **Any other DIFF** is a real difference and must be reported.

## 7. Clean up

- **After a verified restore:** `sudo shred -u "$S/env.backup" "$S/creds"`. The rest of `$S` (`state.json`, `window.log`) holds no secret value and stays as evidence.
- **Report:** the steps taken and the outputs of step 6.
