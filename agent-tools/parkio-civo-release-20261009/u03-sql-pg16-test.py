"""U03 role statements against a disposable PostgreSQL 16 with the deployed auth-service migrations.

The running auth-service image is built from 504b9cb4 (revision label). Extract its migrations, then run:
  mkdir mig && for f in $(git ls-tree --name-only 504b9cb4 services/auth-service/src/main/resources/db/migration/); do
    git show "504b9cb4:$f" > "mig/$(basename "$f")"; done
  python3 -I u03-sql-pg16-test.py u05-priv001a-window.py mig
It starts one postgres:16-alpine container (removed at the end), applies every migration and checks the runner's
elevation and demotion statements, including their refusals.
"""
import importlib.util, os, re, secrets, subprocess, sys, time, uuid

runner_path, mig_dir = sys.argv[1:3]
spec = importlib.util.spec_from_file_location("runner", runner_path)
R = importlib.util.module_from_spec(spec)
spec.loader.exec_module(R)

name = f"u03-sqltest-{secrets.token_hex(4)}"
pw = secrets.token_hex(12)
ok = fail = 0


def check(label, cond):
    global ok, fail
    print(("ok   " if cond else "FAIL ") + label, flush=True)
    ok, fail = ok + bool(cond), fail + (not cond)


def sh(args, inp=None, timeout=120):
    p = subprocess.run(args, input=inp, text=True, capture_output=True, timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def psql(sql, ro=False):
    args = ["docker", "exec", "-i"]
    if ro:
        args += ["-e", "PGOPTIONS=-c default_transaction_read_only=on"]
    args += [name, "psql", "-X", "-q", "-At", "-F", "|", "--set=ON_ERROR_STOP=1", "-U", "parkio_auth", "-d", "parkio_auth"]
    rc, out, err = sh(args, inp=sql)
    if rc != 0:
        raise RuntimeError(err.strip()[:300])
    return [ln.split("|") for ln in out.splitlines() if ln.strip()]


rc, out, err = sh(["docker", "run", "-d", "--rm", "--name", name, "-e", f"POSTGRES_PASSWORD={pw}",
                   "-e", "POSTGRES_USER=parkio_auth", "-e", "POSTGRES_DB=parkio_auth", "postgres:16-alpine"])
assert rc == 0, err
try:
    for _ in range(60):
        if sh(["docker", "exec", name, "pg_isready", "-U", "parkio_auth", "-d", "parkio_auth"])[0] == 0:
            break
        time.sleep(1)
    time.sleep(2)
    migs = sorted(os.listdir(mig_dir), key=lambda f: int(re.match(r"V(\d+)__", f).group(1)))
    for f in migs:
        psql(open(os.path.join(mig_dir, f)).read())
    last = re.match(r"V(\d+)", migs[-1]).group(1)
    check(f"{len(migs)} auth-service migrations applied (V1..V{last})", True)
    roles = dict((n, i) for i, n in psql("select id, name from roles"))
    check(f"roles present: {sorted(roles)}", {"USER", "ADMIN", "SUPER_ADMIN"} <= set(roles))
    cols = [r[0] for r in psql("select column_name from information_schema.columns where table_name = 'auth_users'")]

    def user(email, status="ACTIVE", created="now()"):
        uid = str(uuid.uuid4())
        vals = {"id": f"'{uid}'::uuid", "email": f"'{email}'", "status": f"'{status}'", "created_at": created}
        req = [r for r in psql("select column_name, column_default, is_nullable from information_schema.columns "
                               "where table_name = 'auth_users'") if r[2] == "NO" and not r[1]]
        for c, _, _ in req:
            if c not in vals:
                vals[c] = {"password_hash": "'x'", "email_verified": "true"}.get(c, "'x'")
        psql(f"insert into auth_users ({', '.join(vals)}) values ({', '.join(vals.values())})")
        psql(f"insert into auth_user_roles (user_id, role_id) values ('{uid}'::uuid, '{roles['USER']}'::uuid)")
        return uid

    w1 = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - 60))
    syn = lambda: f"priv001a-u03{secrets.token_hex(8)}@priv001a.parkio.invalid"
    x_email, old_email, er_email = syn(), syn(), syn()
    x = user(x_email)
    old = user(old_email, created="now() - interval '2 hours'")
    er = user(er_email)
    psql(f"insert into erasure_requests (id, auth_user_id, status, requested_at) values "
         f"('{uuid.uuid4()}'::uuid, '{er}'::uuid, 'IN_PROGRESS', now())")
    real = user("someone@example.com")
    epoch = lambda u: int(psql(f"select session_epoch from auth_users where id = '{u}'::uuid", ro=True)[0][0])
    rset = lambda u: sorted(r[0] for r in psql(
        f"select r.name from auth_user_roles ur join roles r on r.id = ur.role_id where ur.user_id = '{u}'::uuid", ro=True))
    e0 = epoch(x)
    rows = psql(R.u03_elevate_sql(x, x_email, w1))
    check("elevate: exactly the synthetic account, one row", len(rows) == 1 and rows[0][0] == x)
    check("elevate: roles ADMIN+USER, session epoch +1", rset(x) == ["ADMIN", "USER"] and epoch(x) == e0 + 1)
    check("elevate again: 0 rows (holds a role other than USER)", psql(R.u03_elevate_sql(x, x_email, w1)) == [])
    check("elevate an account registered before W1: 0 rows", psql(R.u03_elevate_sql(old, old_email, w1)) == [] and rset(old) == ["USER"])
    check("elevate an account with an erasure request: 0 rows", psql(R.u03_elevate_sql(er, er_email, w1)) == [] and rset(er) == ["USER"])
    check("elevate with a mismatched email: 0 rows", psql(R.u03_elevate_sql(old, x_email, w1)) == [])
    try:
        R.u03_elevate_sql(real, "someone@example.com", w1)
        check("a non-synthetic email is refused before any SQL", False)
    except R.Stop:
        check("a non-synthetic email is refused before any SQL", True)
    try:
        R.psql_write("update auth_users set status = 'ACTIVE'")
        check("psql_write refuses any other statement", False)
    except R.Stop:
        check("psql_write refuses any other statement", True)
    e1 = epoch(x)
    rows = psql(R.u03_demote_sql(x))
    check("demote: one row; roles USER; session epoch +1", len(rows) == 1 and rset(x) == ["USER"] and epoch(x) == e1 + 1)
    check("demote again: 0 rows, epoch unchanged", psql(R.u03_demote_sql(x)) == [] and epoch(x) == e1 + 1)
    check("the other accounts are untouched", rset(real) == ["USER"] and epoch(real) == 0 and epoch(old) == 0 and epoch(er) == 0)
    psql(f"insert into auth_user_roles (user_id, role_id) values ('{real}'::uuid, '{roles['ADMIN']}'::uuid)")
    check("the demotion never touches a non-synthetic account (0 rows; its ADMIN stays)",
          psql(R.u03_demote_sql(real)) == [] and rset(real) == ["ADMIN", "USER"] and epoch(real) == 0)
    trick = user("priv001a-u03trick@x@priv001a.parkio.invalid")
    psql(f"insert into auth_user_roles (user_id, role_id) values ('{trick}'::uuid, '{roles['ADMIN']}'::uuid)")
    check("the demotion's tie is the exact allowlist (a double-@ address is not synthetic)",
          psql(R.u03_demote_sql(trick)) == [] and rset(trick) == ["ADMIN", "USER"])
    v = psql(f"select version from auth_users where id = '{x}'::uuid", ro=True)[0][0] if "version" in cols else "n/a"
    check(f"version bumped twice (as two JPA saves): {v}", v in ("2", "n/a"))
finally:
    sh(["docker", "rm", "-f", "-v", name])      # -v: the image's anonymous data volume too
print(f"=== {ok} ok, {fail} FAIL ===")
sys.exit(1 if fail else 0)
