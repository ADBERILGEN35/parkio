#!/usr/bin/env python3
"""Disposable auth+gateway candidate-image acceptance (U03 artifact-level acceptance).

Reused from the U03/U04 release package (22/22 on 2026-09-28). Exercises unpublished
auth-service and gateway-service images together on the stack in docker-compose.yml next to
this file: privileged-token revocation within the configured epoch-cache bound (fixture PT3S),
fail-closed epoch lookups, direct-ingress protection and single-session revocation. Synthetic
accounts and isolated databases only. Not a production test.

The workflow starts and stops the stack; this runner only talks to it. Results go to
$CANDIDATE_ACCEPTANCE_OUT/acceptance-image-results.json (default: next to this file).
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
GATEWAY = os.environ.get("GATEWAY_BASE", "http://127.0.0.1:18080")
AUTH = os.environ.get("AUTH_BASE", "http://127.0.0.1:18081")
STUBS = os.environ.get("STUBS_BASE", "http://127.0.0.1:18099")
COMPOSE_FILE = ROOT / "docker-compose.yml"
OUT_DIR = Path(os.environ.get("CANDIDATE_ACCEPTANCE_OUT", str(ROOT)))
AUTH_CONTAINER = os.environ.get("CANDIDATE_AUTH_CONTAINER", "parkio-candidate-acc-auth-service-1")
PASSWORD = "DisposableU03u04!A1"
REASON = "synthetic-candidate-disposable-acceptance"
CACHE_TTL_S = 3
CACHE_WAIT_S = 4.2
RESULTS: list[dict] = []


def compose(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["docker", "compose", "-f", str(COMPOSE_FILE), *args],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def record(name: str, ok: bool, detail: dict) -> None:
    RESULTS.append({"name": name, "ok": ok, "detail": detail})
    status = "PASS" if ok else "FAIL"
    print(f"[{status}] {name} {json.dumps(detail, ensure_ascii=True)}", flush=True)


def http(
    method: str,
    url: str,
    *,
    body: dict | None = None,
    token: str | None = None,
    headers: dict | None = None,
    timeout: float = 20,
) -> tuple[int, dict | str, dict]:
    raw = None if body is None else json.dumps(body).encode()
    hdrs = {"Accept": "application/json"}
    if raw is not None:
        hdrs["Content-Type"] = "application/json"
    if token:
        hdrs["Authorization"] = f"Bearer {token}"
    hdrs["X-Parkio-Client"] = "mobile"
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(url, data=raw, method=method, headers=hdrs)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = resp.read()
            parsed: dict | str
            if payload:
                try:
                    parsed = json.loads(payload.decode())
                except json.JSONDecodeError:
                    parsed = payload.decode(errors="replace")
            else:
                parsed = {}
            return resp.status, parsed, dict(resp.headers)
    except urllib.error.HTTPError as ex:
        payload = ex.read()
        parsed: dict | str
        if payload:
            try:
                parsed = json.loads(payload.decode())
            except json.JSONDecodeError:
                parsed = payload.decode(errors="replace")
        else:
            parsed = {}
        return ex.code, parsed, dict(ex.headers)


def code_of(body: dict | str) -> str | None:
    if isinstance(body, dict):
        return body.get("code")
    return None


def wait_cache() -> None:
    time.sleep(CACHE_WAIT_S)


def set_epoch_mode(mode: str) -> None:
    status, body, _ = http("POST", f"{STUBS}/control/mode", body={"epoch": mode})
    if status != 200:
        raise RuntimeError(f"stub mode {mode} failed: {status} {body}")


def auth_logs() -> str:
    proc = subprocess.run(
        ["docker", "logs", "--since", "3m", AUTH_CONTAINER],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return (proc.stdout or "") + (proc.stderr or "")


def latest_token_for(email: str) -> str:
    logs = auth_logs()
    joined = logs.replace("\r", "")
    pattern = re.compile(
        r"Email verification link for " + re.escape(email) + r": (\S+)",
        re.I,
    )
    matches = pattern.findall(joined)
    if not matches:
        nearby = [ln for ln in joined.splitlines() if "Email verification" in ln or "verify-email?token=" in ln]
        raise RuntimeError(f"no verification link logged for {email}; seen={nearby[-5:]}")
    url = matches[-1]
    m = re.search(r"[?&]token=([^&\s]+)", url)
    if not m:
        raise RuntimeError(f"no token in logged URL for {email}: {url}")
    return m.group(1)


def register_verify_login(email: str) -> tuple[str, str, str]:
    status, body, _ = http(
        "POST",
        f"{GATEWAY}/api/v1/auth/register",
        body={"email": email, "password": PASSWORD, "locale": "en"},
    )
    if status not in (200, 201) or not isinstance(body, dict):
        raise RuntimeError(f"register {email}: {status} {body}")
    user = body.get("user") or {}
    user_id = str(user.get("id"))
    token = None
    last_err = None
    for _ in range(40):
        time.sleep(0.5)
        try:
            token = latest_token_for(email)
            break
        except RuntimeError as ex:
            last_err = ex
            token = None
    if not token:
        raise RuntimeError(f"verification token missing for {email}: {last_err}")
    if not token:
        raise RuntimeError(f"verification token missing for {email}")
    status, body, _ = http(
        "POST",
        f"{GATEWAY}/api/v1/auth/verify-email",
        body={"token": token},
    )
    if status != 200:
        raise RuntimeError(f"verify {email}: {status} {body}")
    status, body, _ = http(
        "POST",
        f"{GATEWAY}/api/v1/auth/login",
        body={"email": email, "password": PASSWORD},
    )
    if status != 200 or not isinstance(body, dict) or not body.get("accessToken"):
        raise RuntimeError(f"login {email}: {status} {body}")
    return user_id, body["accessToken"], body.get("refreshToken") or ""


def login(email: str) -> tuple[str, str]:
    status, body, _ = http(
        "POST",
        f"{GATEWAY}/api/v1/auth/login",
        body={"email": email, "password": PASSWORD},
    )
    if status != 200 or not isinstance(body, dict) or not body.get("accessToken"):
        raise RuntimeError(f"re-login {email}: {status} {body}")
    return body["accessToken"], body.get("refreshToken") or ""


def bootstrap(email: str) -> None:
    status, body, _ = http(
        "POST",
        f"{AUTH}/internal/auth/admin/bootstrap-super-admin",
        body={"email": email},
        headers={
            "X-Gateway-Auth": "disposable-gateway-internal-secret-u03u04",
            "X-Parkio-Admin-Bootstrap-Token": "disposable-bootstrap-token-u03u04-32chars",
        },
    )
    if status not in (200, 204):
        raise RuntimeError(f"bootstrap: {status} {body}")


def admin_get(token: str) -> tuple[int, dict | str]:
    status, body, _ = http("GET", f"{GATEWAY}/api/v1/admin/users?size=50", token=token)
    return status, body


def change_role(token: str, user_id: str, role: str, action: str) -> None:
    status, body, _ = http(
        "POST",
        f"{GATEWAY}/api/v1/admin/users/{user_id}/roles",
        token=token,
        body={"role": role, "action": action, "reason": REASON},
    )
    if status not in (200, 204):
        raise RuntimeError(f"{action} {role}: {status} {body}")


def revoke_all(token: str, user_id: str) -> None:
    status, body, _ = http(
        "POST",
        f"{GATEWAY}/api/v1/admin/users/{user_id}/revoke-sessions",
        token=token,
        body={"reason": REASON},
    )
    if status not in (200, 204):
        raise RuntimeError(f"revoke-all: {status} {body}")


def list_sessions(token: str, user_id: str) -> list:
    status, body, _ = http(
        "GET",
        f"{GATEWAY}/api/v1/admin/users/{user_id}/sessions",
        token=token,
    )
    if status != 200 or not isinstance(body, list):
        raise RuntimeError(f"list sessions: {status} {body}")
    return body


def revoke_session(token: str, user_id: str, session_id: str) -> None:
    status, body, _ = http(
        "DELETE",
        f"{GATEWAY}/api/v1/admin/users/{user_id}/sessions/{session_id}",
        token=token,
        body={"reason": REASON},
    )
    if status not in (200, 204):
        raise RuntimeError(f"revoke session: {status} {body}")


def expect_admin(token: str, expected_status: int, expected_code: str | None = None) -> tuple[bool, dict]:
    status, body = admin_get(token)
    ok = status == expected_status
    if expected_code is not None:
        ok = ok and code_of(body) == expected_code
    return ok, {"status": status, "code": code_of(body), "body": body if status >= 400 else "omitted"}


def users_me(token: str) -> tuple[int, dict | str]:
    status, body, _ = http("GET", f"{GATEWAY}/api/v1/users/me", token=token)
    return status, body


def main() -> int:
    stamp = time.strftime("%Y%m%d%H%M%S")
    super_email = f"u03-super-{stamp}@parkio.test"
    admin_email = f"u03-admin-{stamp}@parkio.test"
    revoke_email = f"u03-revokeall-{stamp}@parkio.test"
    fail_email = f"u03-failclosed-{stamp}@parkio.test"

    super_id, t0, _ = register_verify_login(super_email)
    me_status, me_body = users_me(t0)
    record(
        "matching-epoch-0-reaches-downstream-not-token-revoked",
        me_status not in (401, 503) and code_of(me_body) not in ("TOKEN_REVOKED", "SESSION_EPOCH_UNAVAILABLE"),
        {"status": me_status, "code": code_of(me_body), "note": "stub user-service 404 is allowed"},
    )
    admin_status, admin_body = admin_get(t0)
    record(
        "epoch-0-user-still-forbidden-on-admin-not-revoked",
        admin_status == 403,
        {"status": admin_status, "code": code_of(admin_body)},
    )

    bootstrap(super_email)
    me_imm, me_imm_body = users_me(t0)
    record(
        "epoch-0-after-bootstrap-within-cache-still-matches",
        me_imm not in (401, 503) and code_of(me_imm_body) not in ("TOKEN_REVOKED", "SESSION_EPOCH_UNAVAILABLE"),
        {"status": me_imm, "code": code_of(me_imm_body), "note": "cached epoch 0 equals token"},
    )
    wait_cache()
    me_after, me_after_body = users_me(t0)
    record(
        "epoch-0-token-revoked-after-bootstrap-beyond-cache",
        me_after == 401 and code_of(me_after_body) == "TOKEN_REVOKED",
        {"status": me_after, "code": code_of(me_after_body), "body": me_after_body},
    )

    super_token, _ = login(super_email)
    ok, detail = expect_admin(super_token, 200)
    record("super-admin-after-relogin", ok, {**detail, "roles_path": "login"})

    admin_id, admin_pre, _ = register_verify_login(admin_email)
    change_role(super_token, admin_id, "ADMIN", "GRANT")
    wait_cache()
    admin_token, _ = login(admin_email)
    ok, detail = expect_admin(admin_token, 200)
    record("admin-token-valid-after-grant", ok, detail)

    change_role(super_token, admin_id, "ADMIN", "REVOKE")
    ok_within, d_within = expect_admin(admin_token, 200)
    record(
        "role-removal-within-configured-cache-ttl-still-accepted",
        ok_within,
        {**d_within, "cache_ttl": "PT3S"},
    )
    wait_cache()
    ok_after, d_after = expect_admin(admin_token, 401, "TOKEN_REVOKED")
    record("role-removal-beyond-cache-ttl-token-revoked", ok_after, d_after)

    revoke_id, _, _ = register_verify_login(revoke_email)
    change_role(super_token, revoke_id, "ADMIN", "GRANT")
    wait_cache()
    revoke_token, _ = login(revoke_email)
    ok, detail = expect_admin(revoke_token, 200)
    record("revoke-all-subject-admin-ready", ok, detail)
    revoke_all(super_token, revoke_id)
    ok_w, d_w = expect_admin(revoke_token, 200)
    record("revoke-all-within-cache-ttl-still-accepted", ok_w, d_w)
    wait_cache()
    ok_a, d_a = expect_admin(revoke_token, 401, "TOKEN_REVOKED")
    record("revoke-all-beyond-cache-ttl-token-revoked", ok_a, d_a)

    fail_id, _, _ = register_verify_login(fail_email)
    change_role(super_token, fail_id, "ADMIN", "GRANT")
    wait_cache()
    fail_token, _ = login(fail_email)
    ok, detail = expect_admin(fail_token, 200)
    record("fail-closed-subject-primed-then-wait", ok, detail)
    wait_cache()

    for mode in ("null", "malformed", "mismatch", "empty", "missing"):
        set_epoch_mode(mode)
        ok, detail = expect_admin(fail_token, 503, "SESSION_EPOCH_UNAVAILABLE")
        record(f"fail-closed-epoch-{mode}", ok, {**detail, "mode": mode})
        wait_cache()
    set_epoch_mode("proxy")
    wait_cache()
    ok, detail = expect_admin(fail_token, 200)
    record("fail-closed-restored-proxy", ok, detail)

    status, body, _ = http("GET", f"{AUTH}/api/v1/admin/users")
    ok = status == 401
    record("direct-auth-admin-without-gateway-secret", ok, {"status": status, "code": code_of(body)})
    status, body, _ = http(
        "GET",
        f"{AUTH}/api/v1/admin/users",
        headers={"X-Gateway-Auth": "wrong-secret"},
    )
    ok = status == 401
    record("direct-auth-admin-wrong-gateway-secret", ok, {"status": status, "code": code_of(body)})

    sess_email = f"u03-session-{stamp}@parkio.test"
    sess_id, _, _ = register_verify_login(sess_email)
    change_role(super_token, sess_id, "ADMIN", "GRANT")
    wait_cache()
    def active_ids() -> set[str]:
        return {str(s.get("sessionId")) for s in list_sessions(super_token, sess_id) if not s.get("revoked")}

    before = active_ids()
    access1, refresh1 = login(sess_email)
    after1 = active_ids()
    sid1_set = after1 - before
    access2, refresh2 = login(sess_email)
    after2 = active_ids()
    sid2_set = after2 - after1
    if len(sid1_set) != 1 or len(sid2_set) != 1:
        record(
            "single-session-two-families",
            False,
            {"before": sorted(before), "after1": sorted(after1), "after2": sorted(after2)},
        )
    else:
        first_id = next(iter(sid1_set))
        revoke_session(super_token, sess_id, first_id)
        ok1, d1 = expect_admin(access1, 200)
        ok2, d2 = expect_admin(access2, 200)
        rstatus, rbody, _ = http(
            "POST",
            f"{GATEWAY}/api/v1/auth/refresh-token",
            body={"refreshToken": refresh1},
        )
        refresh_dead = rstatus in (401, 403, 400)
        r2status, r2body, _ = http(
            "POST",
            f"{GATEWAY}/api/v1/auth/refresh-token",
            body={"refreshToken": refresh2},
        )
        refresh2_ok = r2status == 200 and isinstance(r2body, dict) and bool(r2body.get("accessToken"))
        record(
            "single-session-revoke-does-not-epoch-invalidate-access-tokens",
            ok1 and ok2,
            {"access1": d1, "access2": d2, "revokedSession": first_id},
        )
        record(
            "single-session-revoke-kills-that-refresh-family-only",
            refresh_dead and refresh2_ok,
            {
                "revokedFamilyRefresh": {"status": rstatus, "code": code_of(rbody)},
                "otherFamilyRefresh": {"status": r2status, "code": code_of(r2body) if r2status >= 400 else "ok"},
            },
        )

    out = OUT_DIR / "acceptance-image-results.json"
    summary = {
        "class": "candidate-image",
        "auth_image": os.environ.get("AUTH_IMAGE"),
        "gateway_image": os.environ.get("GATEWAY_IMAGE"),
        "not_source_tests": True,
        "cache_ttl": "PT3S",
        "passed": sum(1 for r in RESULTS if r["ok"]),
        "failed": sum(1 for r in RESULTS if not r["ok"]),
        "results": RESULTS,
    }
    out.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps({"passed": summary["passed"], "failed": summary["failed"]}, indent=2))
    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as ex:
        record("runner-exception", False, {"error": str(ex)})
        out = OUT_DIR / "acceptance-image-results.json"
        out.write_text(
            json.dumps({"passed": 0, "failed": 1, "results": RESULTS, "error": str(ex)}, indent=2),
            encoding="utf-8",
        )
        print(f"RUNNER ERROR: {ex}", file=sys.stderr)
        raise SystemExit(2)
