#!/usr/bin/env python3
"""U05 media rollout prerequisites, read-only (Asana 1219100261876615; contract docs/architecture/erasure-ack-outbox-contract.md,
"Storage topology", "Bounded attempts", "PUT address fallback", "V14 edited in place").

Run on parkio-civo-prod as civo, with sudo:
  sudo python3 -I /home/civo/parkio-release-20261009/tools/u05-media-readonly.py

It finds the running minio, media-service and postgres-media containers of one Compose project, then records:
  1 identity     the media image, its revision label and the MinIO image;
  2 bucket mode  versioning (unversioned, Enabled, Suspended) and object lock of the media bucket (`mc stat`). Deletes work by
                 version, so every versioning state is handled; an object-lock bucket fails closed while retention holds;
  3 permissions  the identity media-service signs with. When its access key is MinIO's root user, every S3 action is allowed.
                 Otherwise its policies must allow s3:ListBucketVersions on the bucket and s3:DeleteObjectVersion on its objects;
  4 region       the region media-service signs for (PARKIO_MEDIA_STORAGE_REGION, default us-east-1) against the bucket's
                 location as MinIO reports it. Equal: no region redirect and no extra GetBucketLocation call;
  5 network      PARKIO_MEDIA_STORAGE_ENDPOINT resolves, inside the media container, to exactly one address, the MinIO
                 container's own address on a network both share; no proxy variable or JVM proxy property;
  6 flyway       the media database's flyway_schema_history against the checksums of the migrations in the deployed image's
                 source; V14 must carry the final checksum, not the earlier pre-review one;
  7 erasure      counts only: media_erasure_jobs rows and their highest attempt count, media_object_writes rows per state.
Read-only: docker ps / inspect, and docker exec of read-only commands in the three running containers. In the MinIO container an
mc alias is made from that container's own credentials (piped by the shell builtin printf, never on a command line) in a fresh
directory under its tmpfs /tmp, used for GET requests (stat, admin config get, admin user info, admin policy info) and deleted
in the same exec. In the media container: getent. In the database container: psql in a read-only transaction. No restart, no
configuration change, no new container, no pull. No credential, object key or identifier is printed or recorded.
Evidence: ~civo/parkio-release-20261009/u05/<run stamp>/media-readonly.json (dir 700, file 600).
Exit 0 when the record was written (FINDING and UNKNOWN items are results, shown in the table); 1 when it was not.
"""
import fnmatch, json, os, re, subprocess, sys, time, urllib.parse

OUT = os.environ.get("U05_OUT", "/home/civo/parkio-release-20261009")
OWNER = os.environ.get("U05_OWNER", "civo")
PROJECT = os.environ.get("U05_PROJECT", "")
DOCKER = os.environ.get("U05_DOCKER", "docker")
TEST = bool(os.environ.get("U05_TEST"))
RUNSTAMP = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
# Flyway checksums (Flyway 11 ChecksumCalculator, cross-checked) of every media migration. They are identical at the release
# PIN f888a81b and at 504b9cb4, the revision of the deployed media image (U13 reconciliation 2026-10-12).
SOURCE_COMMITS = ("f888a81b113c0c486ea0949067779443857e644d", "504b9cb453aca31954800cb4f73a074f3b8f5ef6")
EXPECTED = {1: -1965765461, 2: 1910943609, 3: 238969686, 4: -1774964467, 5: 2032215871, 6: -1348400959, 7: 654333862,
            8: -374629478, 9: -1227188171, 10: 2058051955, 11: -1588362558, 12: -1657915835, 13: -249495841,
            14: 2068638586, 15: -779929021, 16: 1619066448, 17: -745216325, 18: -1697898506}
V14_EARLIER = -1766445011   # blob 98bdac75, the V14 before its in-place edit (contract: "V14 edited in place")
PROXY_VARS = ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")
JVM_OPT_VARS = ("JAVA_TOOL_OPTIONS", "JAVA_OPTS", "JDK_JAVA_OPTIONS")
JVM_PROXY = re.compile(r"-D(https?\.proxyHost|socksProxyHost|java\.net\.useSystemProxies=true)")

MC_SCRIPT = r'''
set -u
d="$(mktemp -d /tmp/.mc-u05.XXXXXX)" || { echo '{"u05":"no-tmp"}'; exit 0; }
trap 'rm -rf "$d"' EXIT
export MC_CONFIG_DIR="$d"
if ! printf '%s\n%s\n' "${MINIO_ROOT_USER:-}" "${MINIO_ROOT_PASSWORD:-}" | mc --json alias set u05 http://127.0.0.1:9000 >/dev/null 2>&1; then
  echo '{"u05":"alias-failed"}'; exit 0
fi
case "$1" in
  bucket)
    echo '{"u05":"stat"}'; mc --json stat "u05/$2" 2>&1 | tr -d '\n'; echo
    echo '{"u05":"site"}'; mc --json admin config get u05 site 2>&1 | tr -d '\n'; echo
    if [ -n "${U05_AK:-}" ]; then echo '{"u05":"user"}'; mc --json admin user info u05 "$U05_AK" 2>&1 | tr -d '\n'; echo; fi ;;
  policy)
    shift; for p in "$@"; do echo '{"u05":"policy"}'; mc --json admin policy info u05 "$p" 2>&1 | tr -d '\n'; echo; done ;;
esac
'''


def run(args, env=None, timeout=120):
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=timeout, env=env)
        return p.returncode, p.stdout, p.stderr
    except (OSError, subprocess.TimeoutExpired) as e:
        return 127, "", str(e)


def stop(msg):
    print(f"STOP: {msg}")
    print("U05 MEDIA READ-ONLY NOT RECORDED - nothing was changed")
    sys.exit(1)


def find(service):
    args = [DOCKER, "ps", "--filter", f"label=com.docker.compose.service={service}",
            "--format", '{{.ID}}|{{.Names}}|{{.Label "com.docker.compose.project"}}']
    if PROJECT:
        args[2:2] = ["--filter", f"label=com.docker.compose.project={PROJECT}"]
    rc, out, err = run(args)
    if rc != 0:
        stop(f"docker ps failed ({err.strip()[:160]})")
    return [dict(zip(("id", "name", "project"), line.split("|", 2))) for line in out.splitlines() if line.strip()]


def inspect(cid):
    rc, out, err = run([DOCKER, "inspect", cid])
    if rc != 0:
        stop(f"docker inspect {cid} failed")
    return json.loads(out)[0]


def envmap(c):
    m = {}
    for kv in c.get("Config", {}).get("Env") or []:
        k, _, v = kv.partition("=")
        m[k] = v
    return m


def mc(minio_id, args, ak=None):
    env = dict(os.environ)
    extra = []
    if ak:
        env["U05_AK"] = ak
        extra = ["-e", "U05_AK"]
    rc, out, err = run([DOCKER, "exec", *extra, minio_id, "bash", "-c", MC_SCRIPT, "u05", *args], env=env)
    sections, cur = {}, None
    for line in out.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except ValueError:
            continue
        if isinstance(obj, dict) and set(obj) == {"u05"}:
            cur = obj["u05"]
            if cur in ("no-tmp", "alias-failed"):
                return {"error": cur}
            continue
        if cur:
            sections.setdefault(cur, []).append(obj)
    if rc != 0 and not sections:
        return {"error": f"docker exec failed (exit {rc})"}
    return sections


def conditional(policies):
    return any(isinstance(st, dict) and st.get("Condition")
               for pol in policies for st in ([pol.get("Statement")] if isinstance(pol.get("Statement"), dict)
                                              else (pol.get("Statement") or [])))


def allows(policies, action, resource):
    allowed, denied = False, False
    for pol in policies:
        stmts = pol.get("Statement") or []
        if isinstance(stmts, dict):
            stmts = [stmts]
        for st in stmts:
            acts = st.get("Action") or []
            res = st.get("Resource") or []
            acts = [acts] if isinstance(acts, str) else acts
            res = [res] if isinstance(res, str) else res
            if any(fnmatch.fnmatchcase(action, a) for a in acts) and any(fnmatch.fnmatchcase(resource, r) for r in res):
                if st.get("Effect") == "Deny":
                    denied = True
                elif st.get("Effect") == "Allow":
                    allowed = True
    return allowed and not denied


def psql(pg_id, sql):
    rc, out, err = run([DOCKER, "exec", "-e", "PGOPTIONS=-c default_transaction_read_only=on", pg_id, "sh", "-c",
                        'psql -X -A -t -F "|" -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"', "u05", sql])
    return rc, out, err


def main():
    print(f"U05 media read-only checks | {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())} | nothing is changed")
    if not TEST and os.geteuid() != 0:
        stop("run as root (sudo python3 -I ...)")
    print("== 0. containers")
    found = {s: find(s) for s in ("minio", "media-service", "postgres-media")}
    for s, cs in found.items():
        if len(cs) != 1:
            stop(f"{len(cs)} running '{s}' container(s){' in project ' + PROJECT if PROJECT else ''} (want exactly 1): "
                 + ", ".join(c["name"] for c in cs))
    projects = {cs[0]["project"] for cs in found.values()}
    if len(projects) != 1:
        stop(f"the three containers belong to different Compose projects: {sorted(projects)}")
    minio_c, media_c, pg_c = (inspect(found[s][0]["id"]) for s in ("minio", "media-service", "postgres-media"))
    names = {s: found[s][0]["name"] for s in found}
    print(f"  project {projects.pop()}: {names['minio']}, {names['media-service']}, {names['postgres-media']}")
    menv, ienv = envmap(media_c), envmap(minio_c)
    rec = {"tool": "u05-media-readonly", "runStamp": RUNSTAMP, "containers": names, "checks": {}}
    rows = []

    def put(key, verdict, detail, **data):
        rec["checks"][key] = dict(verdict=verdict, detail=detail, **data)
        rows.append((key, verdict, detail))

    # 1 identity
    rev = (media_c.get("Config", {}).get("Labels") or {}).get("org.opencontainers.image.revision", "")
    ident = {"mediaImage": media_c.get("Config", {}).get("Image"), "mediaImageId": media_c.get("Image"),
             "mediaRevision": rev or None, "minioImage": minio_c.get("Config", {}).get("Image"),
             "mediaDbImage": pg_c.get("Config", {}).get("Image")}
    known = bool(rev) and any(c.startswith(rev) or rev.startswith(c) for c in SOURCE_COMMITS)
    put("identity", "RECORDED" if known else "NOTE",
        f"media revision {rev[:12] or 'unlabelled'}"
        + ("" if known else f" is not {SOURCE_COMMITS[1][:12]} or the PIN: the expected checksums below may not cover it"),
        **ident)

    # 2-3 bucket mode, permissions
    bucket = menv.get("PARKIO_MEDIA_BUCKET") or "parkio-media"
    ak = menv.get("PARKIO_MEDIA_STORAGE_ACCESS_KEY", "")
    root = bool(ak) and ak == ienv.get("MINIO_ROOT_USER", "\0")
    sec = mc(minio_c["Id"], ["bucket", bucket], ak=None if root else (ak or None))
    stat = (sec.get("stat") or [{}])[0] if "error" not in sec else {}
    if "error" in sec or stat.get("status") != "success":
        why = sec.get("error") or ((stat.get("error") or {}).get("message") or "no stat result")
        put("bucket-mode", "UNKNOWN", f"bucket {bucket}: {why}")
        location = None
    else:
        vstat = (stat.get("Versioning") or {}).get("status") or "Unversioned"
        lock = (stat.get("ObjectLock") or {}).get("enabled") or "Disabled"
        location = stat.get("location") or "us-east-1"
        policy = (stat.get("Policy") or {}).get("type")
        verdict = "FINDING" if lock == "Enabled" else "PASS"
        put("bucket-mode", verdict,
            f"bucket {bucket}: versioning {vstat}, object lock {lock}"
            + ("; deletes fail closed while a retention period holds (AccountErasureStuck)" if lock == "Enabled"
               else "; every versioning state is handled by the version-wise delete"),
            bucket=bucket, versioning=vstat, objectLock=lock, anonymousPolicy=policy, location=location)
    if not ak:
        put("permissions", "FINDING", "media-service has no PARKIO_MEDIA_STORAGE_ACCESS_KEY", identity="none")
    elif root:
        put("permissions", "PASS", "media-service signs as MinIO's root user: every S3 action is allowed, "
            "including s3:ListBucketVersions and s3:DeleteObjectVersion", identity="root")
    else:
        user = (sec.get("user") or [{}])[0] if "error" not in sec else {}
        pols = [p for p in re.split(r"[,\s]+", user.get("policyName") or "") if p]
        groups = user.get("memberOf") or []
        docs = []
        if pols:
            psec = mc(minio_c["Id"], ["policy", *pols])
            docs = [((p.get("policyInfo") or {}).get("Policy") or {}) for p in psec.get("policy", [])]
        need = {"s3:ListBucketVersions": f"arn:aws:s3:::{bucket}", "s3:DeleteObjectVersion": f"arn:aws:s3:::{bucket}/*"}
        missing = [a for a, r in need.items() if not allows(docs, a, r)]
        if user.get("status") != "success":
            put("permissions", "UNKNOWN", "media-service signs as a non-root MinIO user whose info could not be read",
                identity="user")
        else:
            cond = conditional(docs)
            put("permissions", "FINDING" if (missing or groups or cond) else "PASS",
                "media-service signs as a non-root MinIO user with policies " + (", ".join(pols) or "none")
                + (f"; missing: {', '.join(missing)}" if missing else "; it allows s3:ListBucketVersions and s3:DeleteObjectVersion")
                + ("; group memberships present, review their policies by hand" if groups else "")
                + ("; statements with conditions present, review them by hand" if cond else ""),
                identity="user", policies=pols, missing=missing, groups=len(groups), conditions=cond)

    # 4 region
    if "PARKIO_MEDIA_STORAGE_REGION" in menv:
        region, src = menv["PARKIO_MEDIA_STORAGE_REGION"], "PARKIO_MEDIA_STORAGE_REGION"
    else:
        region, src = "us-east-1", "default of application.yml (variable unset)"
    site = ""
    for kv in (((sec.get("site") or [{}])[0].get("config") or [{}])[0].get("kv") or []) if "error" not in sec else []:
        if kv.get("key") == "region":
            site = kv.get("value") or ""
    envreg = {k: ienv[k] for k in ("MINIO_REGION", "MINIO_SITE_REGION", "MINIO_REGION_NAME") if k in ienv}
    if region == "":
        put("region", "FINDING", "PARKIO_MEDIA_STORAGE_REGION is set but empty: no region is configured, so the SDK adds "
            "GetBucketLocation calls (contract: Bounded attempts)", mediaRegion="", mediaRegionSource=src)
    elif location is None:
        put("region", "UNKNOWN", f"media-service signs for {region} ({src}); the bucket location could not be read",
            mediaRegion=region, mediaRegionSource=src)
    else:
        put("region", "PASS" if region == location else "FINDING",
            f"media-service signs for {region} ({src}); MinIO reports the bucket location {location}"
            + ("" if region == location else ": a mismatch causes region redirects"),
            mediaRegion=region, mediaRegionSource=src, bucketLocation=location, minioSiteRegion=site or "(default)",
            minioRegionEnv=envreg)

    # 5 network
    endpoint = menv.get("PARKIO_MEDIA_STORAGE_ENDPOINT", "")
    u = urllib.parse.urlsplit(endpoint) if endpoint else None
    shown = endpoint
    if u and (u.username or u.password):
        shown = urllib.parse.urlunsplit((u.scheme, u.hostname + (f":{u.port}" if u.port else ""), u.path, "", ""))
    proxies = sorted(k for k in PROXY_VARS if menv.get(k))
    jvm = sorted(k for k in JVM_OPT_VARS if JVM_PROXY.search(menv.get(k, "")))
    dnets = set((media_c.get("NetworkSettings", {}).get("Networks") or {}).keys())
    shared = {}   # network -> the MinIO container's addresses there, for the networks it shares with media-service
    for n, v in (minio_c.get("NetworkSettings", {}).get("Networks") or {}).items():
        ips = [ip for ip in ((v or {}).get("IPAddress"), (v or {}).get("GlobalIPv6Address")) if ip]
        if n in dnets and ips:
            shared[n] = ips
    mine = {ip for ips in shared.values() for ip in ips}
    if not u or not u.hostname:
        put("network", "FINDING", "PARKIO_MEDIA_STORAGE_ENDPOINT is unset: the application default http://localhost:9000 "
            "does not reach MinIO from a container", endpoint=None)
    else:
        rc, out, err = run([DOCKER, "exec", media_c["Id"], "getent", "ahosts", u.hostname])
        addrs = sorted({line.split()[0] for line in out.splitlines() if line.strip()})
        port = u.port or (443 if u.scheme == "https" else 80)
        problems = []
        if rc != 0 or not addrs:
            problems.append(f"{u.hostname} does not resolve inside the media container")
        elif [a for a in addrs if a not in mine]:
            problems.append(f"{u.hostname} resolves to {', '.join(a for a in addrs if a not in mine)}, not to the MinIO "
                            "container on a shared network (an intermediary, or another host)")
        if port != 9000:
            problems.append(f"port {port}, not MinIO's API port 9000")
        if proxies or jvm:
            problems.append("proxy settings present: " + ", ".join(proxies + jvm))
        verdict = "FINDING" if problems else "PASS"
        put("network", verdict,
            f"endpoint {shown}: " + ("; ".join(problems) if problems else
                                     f"{u.hostname} resolves to {', '.join(addrs)}, the MinIO container's own "
                                     f"address{'es' if len(addrs) > 1 else ''} on "
                                     f"{', '.join(n for n, ips in shared.items() if set(ips) & set(addrs))}; no proxy setting"),
            endpoint=shown, resolved=addrs, minioAddresses=shared, proxyVariables=proxies, jvmProxyProperties=jvm)

    # 6 flyway
    rc, out, err = psql(pg_c["Id"], "select installed_rank, coalesce(version, ''), coalesce(checksum::text, ''), success, "
                        "script from flyway_schema_history order by installed_rank")
    if rc != 0:
        put("flyway", "UNKNOWN", f"flyway_schema_history could not be read (psql exit {rc})")
    else:
        hist = []
        for line in out.splitlines():
            p = line.split("|")
            if len(p) >= 5:
                hist.append({"rank": int(p[0]), "version": p[1], "checksum": int(p[2]) if p[2] else None,
                             "success": p[3] == "t", "script": p[4]})
        byv = {}
        for h in hist:
            if h["version"].isdigit():
                byv[int(h["version"])] = h
        failed = [h["version"] for h in hist if not h["success"]]
        same = [v for v in EXPECTED if v in byv and byv[v]["checksum"] == EXPECTED[v]]
        differ = [v for v in EXPECTED if v in byv and byv[v]["checksum"] != EXPECTED[v]]
        absent = [v for v in EXPECTED if v not in byv]
        extra = sorted(v for v in byv if v not in EXPECTED)
        v14 = byv.get(14)
        if v14 is None:
            v14_state = "not applied"
        elif v14["checksum"] == EXPECTED[14]:
            v14_state = "final"
        elif v14["checksum"] == V14_EARLIER:
            v14_state = "EARLIER (pre-review) version"
        else:
            v14_state = "unknown version"
        if len(differ) > 2 and len(differ) >= len(same):
            verdict, why = "UNKNOWN", "most checksums differ from the computed ones: compare by hand"
        elif failed or differ or v14_state != "final":
            verdict, why = "FINDING", ""
        else:
            verdict, why = "PASS", ""
        put("flyway", verdict,
            f"V14 {v14_state} (checksum {v14['checksum'] if v14 else '-'}); {len(same)} of {len(EXPECTED)} expected "
            f"versions match" + (f"; differ: {differ}" if differ else "") + (f"; not applied: {absent}" if absent else "")
            + (f"; failed rows: {failed}" if failed else "") + (f"; newer versions: {extra}" if extra else "")
            + (f"; {why}" if why else ""),
            v14Checksum=v14["checksum"] if v14 else None, v14Expected=EXPECTED[14], v14Earlier=V14_EARLIER,
            v14State=v14_state, matching=same, differing=differ, notApplied=absent, newer=extra, failedRows=failed,
            history=[{k: h[k] for k in ("rank", "version", "checksum", "success", "script")} for h in hist],
            expectedFrom=list(SOURCE_COMMITS))

    # 7 erasure counts
    rc1, out1, _ = psql(pg_c["Id"], "select count(*), coalesce(max(attempts), 0) from media_erasure_jobs")
    rc2, out2, _ = psql(pg_c["Id"], "select state, count(*) from media_object_writes group by state order by state")
    if rc1 == 0 and rc2 == 0 and out1.strip():
        jobs, maxatt = (int(x) for x in out1.strip().split("|")[:2])
        writes = {p.split("|")[0]: int(p.split("|")[1]) for p in out2.splitlines() if "|" in p}
        put("erasure-state", "RECORDED", f"media_erasure_jobs {jobs} row(s), highest attempts {maxatt}; "
            f"media_object_writes {', '.join(f'{k} {v}' for k, v in writes.items()) or 'none'}",
            erasureJobs=jobs, maxAttempts=maxatt, objectWrites=writes)
    else:
        put("erasure-state", "UNKNOWN", "the erasure tables could not be counted")

    print("== 1-7. results")
    for key, verdict, detail in rows:
        print(f"  {key:14} {verdict:8} {detail}")
    evd = os.path.join(OUT, "u05", RUNSTAMP)
    os.makedirs(evd, exist_ok=True)
    path = os.path.join(evd, "media-readonly.json")
    with open(path, "w") as f:
        json.dump(rec, f, indent=2)
    if not TEST:
        subprocess.run(["chown", "-R", f"{OWNER}:{OWNER}", os.path.join(OUT, "u05")], check=False)
    os.chmod(os.path.join(OUT, "u05"), 0o700)
    os.chmod(evd, 0o700)
    os.chmod(path, 0o600)
    import hashlib
    print(f"evidence: {path} sha256 {hashlib.sha256(open(path, 'rb').read()).hexdigest()}")
    counts = {v: sum(1 for _, x, _ in rows if x == v) for v in ("PASS", "FINDING", "UNKNOWN")}
    print(f"U05 MEDIA READ-ONLY RECORDED - {counts['PASS']} PASS, {counts['FINDING']} FINDING, {counts['UNKNOWN']} UNKNOWN")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:   # no traceback: it could quote a value
        stop(f"unexpected {type(e).__name__} in the checks")
