#!/usr/bin/env python3
"""Synthetic-fixture tests for the restore-readiness helpers (no Docker, no network)."""
import calendar
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
STAMP_TOOL = ROOT / "scripts/lib/restore-stamp-preflight.py"
PROFILE_TOOL = ROOT / "scripts/lib/restore-dump-profile.py"
COMPAT_TOOL = ROOT / "scripts/lib/restore-client-compat.py"
ISOLATION_TOOL = ROOT / "scripts/lib/restore-drill-isolation-preflight.py"
DATABASES = ("auth", "gateway", "user", "parking", "media",
             "gamification", "notification", "moderation", "analytics", "ai-validation")
STAMP = "2026-09-20T03-30-01Z"
STAMP_EPOCH = calendar.timegm((2026, 9, 20, 3, 30, 1, 0, 0, 0))
LEDGER_ID = "4f1c2a9e-0000-4000-8000-00000000c0de"
PII = ("alice@example.test", "verify-token-SECRET-123", "+905550000000")


def run(tool, *args, env=None, stdin=None):
    return subprocess.run([sys.executable, str(tool), *map(str, args)], input=stdin,
                          capture_output=True, env=env, check=False)


def write_integrity(stamp):
    """Python mirror of parkio_backup_write_stamp_integrity (find . | sort | sha256sum)."""
    files = sorted(p.relative_to(stamp).as_posix() for p in stamp.rglob("*")
                   if p.is_file() and p.name not in ("SHA256SUMS", "COMPLETE"))
    lines = [f"{hashlib.sha256((stamp / f).read_bytes()).hexdigest()}  ./{f}" for f in files]
    (stamp / "SHA256SUMS").write_text("\n".join(lines) + "\n")
    digest = hashlib.sha256((stamp / "SHA256SUMS").read_bytes()).hexdigest()
    (stamp / "COMPLETE").write_text(f"stamp={stamp.name}\nsha256sums={digest}\n")


def make_stamp(base, offsite_uploaded=True, ledger=None, integrity=True):
    stamp = Path(base) / STAMP
    stamp.mkdir()
    for name in DATABASES:
        dump = stamp / f"{name}.sql.gz.enc"
        dump.write_bytes(b"Salted__" + os.urandom(64))
        digest = hashlib.sha256(dump.read_bytes()).hexdigest()
        (stamp / f"{name}.sql.gz.enc.sha256").write_text(f"{digest}  {dump}\n")
    (stamp / "minio.tar.gz.enc").write_bytes(b"Salted__" + os.urandom(64))
    (stamp / "erasure-tombstones.json").write_text(json.dumps(
        ledger if ledger is not None else [{"authUserId": LEDGER_ID, "erasedAt": "2026-09-01T00:00:00Z"}]))
    (stamp / "backup-manifest.json").write_text(json.dumps({
        "schemaVersion": 3, "timestamp": STAMP, "gitSha": "abc1234",
        "databases": list(DATABASES), "databasesOk": 10, "databasesFailed": 0, "minioOk": 1,
        "minio": {"objectCount": 7, "artifact": "minio.tar.gz.enc"},
        "encryption": {"enabled": True, "algorithm": "aes-256-cbc-pbkdf2"},
        "offsite": {"kind": "azure", "uploaded": offsite_uploaded},
    }))
    if integrity:
        write_integrity(stamp)
    return stamp


def statuses(report):
    return {c["id"]: c["status"] for c in report["checks"]}


class StampPreflightTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.work)

    def preflight(self, stamp, *extra):
        result = run(STAMP_TOOL, stamp, "--now", STAMP_EPOCH + 3600, *extra)
        return result.returncode, json.loads(result.stdout), result.stdout.decode()

    def test_valid_stamp_passes_and_report_leaks_nothing(self):
        code, report, text = self.preflight(make_stamp(self.work))
        self.assertEqual(code, 0, text)
        self.assertEqual(report["verdict"], "PASS")
        self.assertEqual(report["summary"]["erasureTombstones"], 1)
        self.assertEqual(report["summary"]["ageHours"], 1.0)
        self.assertNotIn(LEDGER_ID, text)
        self.assertNotIn(self.work, text)

    def test_bash_integrity_writer_is_accepted(self):
        if not shutil.which("bash") or not shutil.which("sha256sum"):
            self.skipTest("bash/sha256sum unavailable")
        stamp = make_stamp(self.work, integrity=False)
        subprocess.run(["bash", "-c", 'source "$1"; parkio_backup_write_stamp_integrity "$2" "$3"',
                        "_", ROOT / "scripts/lib/backup-common.sh", stamp, STAMP], check=True,
                       capture_output=True)
        code, report, text = self.preflight(stamp)
        self.assertEqual(code, 0, text)

    def test_tampered_dump_fails(self):
        stamp = make_stamp(self.work)
        with open(stamp / "parking.sql.gz.enc", "ab") as handle:
            handle.write(b"x")
        code, report, _ = self.preflight(stamp)
        self.assertEqual(code, 1)
        self.assertEqual(statuses(report)["sha256sums"], "FAIL")

    def test_missing_complete_fails(self):
        stamp = make_stamp(self.work)
        (stamp / "COMPLETE").unlink()
        code, report, _ = self.preflight(stamp)
        self.assertEqual((code, statuses(report)["complete-marker"]), (1, "FAIL"))

    def test_complete_not_binding_sums_fails(self):
        stamp = make_stamp(self.work)
        (stamp / "COMPLETE").write_text(f"stamp={STAMP}\nsha256sums={'0' * 64}\n")
        code, report, _ = self.preflight(stamp)
        self.assertEqual((code, statuses(report)["complete-marker"]), (1, "FAIL"))

    def test_plaintext_dump_and_unlisted_file_fail(self):
        stamp = make_stamp(self.work)
        (stamp / "auth.sql.gz").write_bytes(b"\x1f\x8b plaintext")
        code, report, _ = self.preflight(stamp)
        self.assertEqual(code, 1)
        self.assertEqual(statuses(report)["no-plaintext"], "FAIL")
        self.assertEqual(statuses(report)["unlisted-files"], "FAIL")

    def test_non_ciphertext_dump_fails(self):
        stamp = make_stamp(self.work)
        (stamp / "user.sql.gz.enc").write_bytes(b"\x1f\x8b" + os.urandom(32))
        write_integrity(stamp)
        code, report, _ = self.preflight(stamp)
        self.assertEqual((code, statuses(report)["dump:user"]), (1, "FAIL"))

    def test_missing_database_fails(self):
        stamp = make_stamp(self.work)
        (stamp / "gateway.sql.gz.enc").unlink()
        (stamp / "gateway.sql.gz.enc.sha256").unlink()
        write_integrity(stamp)
        code, report, _ = self.preflight(stamp)
        self.assertEqual((code, statuses(report)["dump:gateway"]), (1, "FAIL"))

    def test_db_only_stamp_is_not_a_full_system_backup(self):
        stamp = make_stamp(self.work)
        (stamp / "minio.tar.gz.enc").unlink()
        manifest = json.loads((stamp / "backup-manifest.json").read_text())
        manifest["minioOk"] = 0
        (stamp / "backup-manifest.json").write_text(json.dumps(manifest))
        write_integrity(stamp)
        code, report, _ = self.preflight(stamp)
        self.assertEqual(code, 1)
        self.assertEqual(statuses(report)["restore-scope"], "FAIL")
        db_code, db_report, _ = self.preflight(stamp, "--scope", "databases")
        self.assertEqual(db_code, 0, db_report)
        self.assertEqual(db_report["verdict"], "PASS")

    def test_missing_ledger_fails_closed(self):
        stamp = make_stamp(self.work)
        (stamp / "erasure-tombstones.json").unlink()
        write_integrity(stamp)
        code, report, _ = self.preflight(stamp)
        self.assertEqual((code, statuses(report)["erasure-ledger"]), (1, "FAIL"))

    def test_ledger_with_personal_fields_fails_without_printing_them(self):
        stamp = make_stamp(self.work, ledger=[{"authUserId": LEDGER_ID, "email": PII[0]}])
        code, report, text = self.preflight(stamp)
        self.assertEqual((code, statuses(report)["erasure-ledger"]), (1, "FAIL"))
        self.assertNotIn(PII[0], text)
        self.assertNotIn(LEDGER_ID, text)

    def test_unsafe_checksum_path_fails(self):
        stamp = make_stamp(self.work)
        with open(stamp / "SHA256SUMS", "a") as handle:
            handle.write(f"{'a' * 64}  ../../etc/passwd\n")
        code, report, _ = self.preflight(stamp)
        self.assertEqual((code, statuses(report)["sha256sums"]), (1, "FAIL"))

    def test_age_limit(self):
        code, report, _ = self.preflight(make_stamp(self.work), "--max-age-hours", "0.5")
        self.assertEqual((code, statuses(report)["stamp-age"]), (1, "FAIL"))

    def test_stale_offsite_flag_is_warning_only(self):
        code, report, _ = self.preflight(make_stamp(self.work, offsite_uploaded=False))
        self.assertEqual(code, 0)
        self.assertEqual(statuses(report)["manifest-offsite-flag"], "WARN")

    def test_missing_directory(self):
        code, report, _ = self.preflight(Path(self.work) / "absent")
        self.assertEqual((code, statuses(report)["stamp-dir"]), (1, "FAIL"))


SYNTHETIC_DUMP = f"""--
-- PostgreSQL database dump
--

-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15

CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;
CREATE TABLE public.auth_users (
    id uuid NOT NULL,
    email text
);
CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL
);
COPY public.auth_users (id, email, token) FROM stdin;
1\t{PII[0]}\t{PII[1]}
2\tbob@example.test\t{PII[2]}
\\.
COPY public.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) FROM stdin;
1\t1\tinit\tSQL\tV1__init.sql\t1\tparkio\t2026-01-01\t5\tt
2\t2\tmore\tSQL\tV2__more.sql\t1\tparkio\t2026-01-02\t5\tt
3\t10\tten\tSQL\tV10__ten.sql\t1\tparkio\t2026-01-03\t5\tt
4\t11\tbroken\tSQL\tV11__broken.sql\t1\tparkio\t2026-01-04\t5\tf
\\.
COPY public.empty_table (id) FROM stdin;
\\.
GRANT SELECT ON TABLE public.auth_users TO parkio_readonly, PUBLIC;
GRANT ALL ON SCHEMA public TO "parkio_migrator";
"""


class DumpProfileTest(unittest.TestCase):
    def setUp(self):
        self.work = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.work)

    def profile(self, text=SYNTHETIC_DUMP):
        return run(PROFILE_TOOL, "profile", stdin=text.encode())

    def test_profile_counts_without_row_contents(self):
        result = self.profile()
        self.assertEqual(result.returncode, 0, result.stderr)
        prof = json.loads(result.stdout)
        self.assertEqual(prof["serverVersion"], "16.15")
        self.assertEqual(prof["pgDumpVersion"], "16.15")
        self.assertEqual(prof["extensions"], ["postgis", "uuid-ossp"])
        self.assertEqual(prof["createTableCount"], 2)
        self.assertEqual(prof["rowCounts"], {"public.auth_users": 2,
                                             "public.flyway_schema_history": 4,
                                             "public.empty_table": 0})
        self.assertEqual(prof["flywayHead"], "10")  # numeric, not lexicographic; failed V11 excluded
        self.assertEqual(prof["flywayFailedRows"], 1)
        self.assertEqual(prof["grantRoles"], ["parkio_readonly", "parkio_migrator"])
        self.assertFalse(prof["restrictCommands"])
        for secret in PII + ("bob@example.test",):
            self.assertNotIn(secret.encode(), result.stdout)

    def test_truncated_copy_fails(self):
        result = self.profile(SYNTHETIC_DUMP.rsplit("\\.", 1)[0])
        self.assertEqual(result.returncode, 1)
        self.assertTrue(json.loads(result.stdout)["truncated"])

    def test_count_sql_and_compare(self):
        prof_path = self.work / "p.json"
        prof_path.write_bytes(self.profile().stdout)
        sql = run(PROFILE_TOOL, "count-sql", prof_path)
        self.assertEqual(sql.returncode, 0)
        self.assertIn(b"FROM public.auth_users", sql.stdout)
        self.assertEqual(sql.stdout.count(b"UNION ALL"), 2)

        counts = self.work / "c.txt"
        counts.write_text("public.auth_users|2\npublic.flyway_schema_history|4\npublic.empty_table|0\n")
        ok = run(PROFILE_TOOL, "compare", prof_path, counts)
        self.assertEqual((ok.returncode, json.loads(ok.stdout)["verdict"]), (0, "PASS"))

        counts.write_text("public.auth_users|1\npublic.flyway_schema_history|4\n")
        bad = run(PROFILE_TOOL, "compare", prof_path, counts)
        report = json.loads(bad.stdout)
        self.assertEqual(bad.returncode, 1)
        self.assertEqual(set(report["mismatches"]), {"public.auth_users", "public.empty_table"})

    def test_compare_excludes_empty_postgis_catalogs_reseeding(self):
        """CI 35887774967: CREATE EXTENSION reseeds catalogs that pg_dump COPY'd as 0."""
        prof_path = self.work / "parking.json"
        prof_path.write_text(json.dumps({
            "rowCounts": {
                "public.parking_spots": 1,
                "public.outbox_events": 1,
                "public.spatial_ref_sys": 0,
                "tiger.pagc_gaz": 0,
                "tiger.pagc_lex": 0,
                "tiger.pagc_rules": 0,
            }
        }))
        counts = self.work / "parking.counts"
        counts.write_text(
            "public.parking_spots|1\npublic.outbox_events|1\n"
            "public.spatial_ref_sys|8500\ntiger.pagc_gaz|835\n"
            "tiger.pagc_lex|2938\ntiger.pagc_rules|4354\n"
        )
        ok = run(PROFILE_TOOL, "compare", prof_path, counts)
        report = json.loads(ok.stdout)
        self.assertEqual((ok.returncode, report["verdict"]), (0, "PASS"))
        self.assertEqual(report["mismatches"], {})
        self.assertEqual(set(report["extensionCatalogsExcluded"]), {
            "public.spatial_ref_sys", "tiger.pagc_gaz", "tiger.pagc_lex", "tiger.pagc_rules",
        })
        self.assertEqual(report["extensionCatalogsExcluded"]["public.spatial_ref_sys"]["restored"], 8500)

    def test_compare_still_fails_application_and_customized_catalogs(self):
        prof_path = self.work / "parking.json"
        prof_path.write_text(json.dumps({
            "rowCounts": {
                "public.parking_spots": 1,
                "public.spatial_ref_sys": 5,
            }
        }))
        counts = self.work / "parking.counts"
        counts.write_text("public.parking_spots|0\npublic.spatial_ref_sys|8500\n")
        bad = run(PROFILE_TOOL, "compare", prof_path, counts)
        report = json.loads(bad.stdout)
        self.assertEqual(bad.returncode, 1)
        self.assertEqual(report["verdict"], "FAIL")
        self.assertEqual(set(report["mismatches"]), {"public.parking_spots", "public.spatial_ref_sys"})
        self.assertNotIn("extensionCatalogsExcluded", report)

    def test_count_sql_refuses_injected_identifier(self):
        prof_path = self.work / "p.json"
        prof_path.write_text(json.dumps({"rowCounts": {"public.x; DROP TABLE y": 1}}))
        result = run(PROFILE_TOOL, "count-sql", prof_path)
        self.assertNotEqual(result.returncode, 0)

    def test_usage(self):
        self.assertEqual(run(PROFILE_TOOL).returncode, 2)

    def test_profile_records_restrict_commands(self):
        dump = "-- Dumped from database version 16.15\n-- Dumped by pg_dump version 16.15\n"
        dump += "\\restrict deadbeefcafebabe\nCREATE TABLE public.t (id int);\n"
        dump += "COPY public.t (id) FROM stdin;\n1\n\\.\n\\unrestrict deadbeefcafebabe\n"
        result = self.profile(dump)
        self.assertEqual(result.returncode, 0, result.stderr)
        prof = json.loads(result.stdout)
        self.assertTrue(prof["restrictCommands"])
        self.assertEqual(prof["rowCounts"], {"public.t": 1})
        self.assertNotIn("deadbeefcafebabe", result.stdout.decode())

    def test_runbook_pipeline_on_real_backup_encryption(self):
        """Same cipher as backup-databases.sh; synthetic passphrase and data only."""
        if not shutil.which("openssl") or not shutil.which("gzip"):
            self.skipTest("openssl/gzip unavailable")
        env = {"PATH": os.environ.get("PATH", ""), "BACKUP_ENCRYPT_PASSPHRASE": "synthetic-drill-only"}
        enc = self.work / "auth.sql.gz.enc"
        subprocess.run(["bash", "-c", "gzip -9 | openssl enc -aes-256-cbc -pbkdf2 -salt "
                        "-pass env:BACKUP_ENCRYPT_PASSPHRASE > \"$1\"", "_", enc],
                       input=SYNTHETIC_DUMP.encode(), env=env, check=True)
        self.assertEqual(enc.read_bytes()[:8], b"Salted__")
        result = subprocess.run(["bash", "-c", "set -o pipefail; openssl enc -d -aes-256-cbc -pbkdf2 "
                                 "-pass env:BACKUP_ENCRYPT_PASSPHRASE < \"$1\" | gunzip "
                                 "| \"$2\" \"$3\" profile", "_", enc, sys.executable, PROFILE_TOOL],
                                env=env, capture_output=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["rowCounts"]["public.auth_users"], 2)
        wrong = subprocess.run(["bash", "-c", "set -o pipefail; openssl enc -d -aes-256-cbc -pbkdf2 "
                                "-pass pass:wrong < \"$1\" | gunzip | \"$2\" \"$3\" profile",
                                "_", enc, sys.executable, PROFILE_TOOL],
                               capture_output=True, check=False)
        self.assertNotEqual(wrong.returncode, 0)


class ClientCompatTest(unittest.TestCase):
    def setUp(self):
        self.work = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.work)

    def write_profile(self, **extra):
        path = self.work / "p.json"
        payload = {"serverVersion": "16.15", "pgDumpVersion": "16.15",
                   "extensions": [], "restrictCommands": False}
        payload.update(extra)
        path.write_text(json.dumps(payload))
        return path

    def compat(self, profile, restore="psql (PostgreSQL) 16.10", server="16.4", postgis=""):
        args = [COMPAT_TOOL, "--dump-profile", profile,
                "--restore-client-version", restore, "--target-server-version", server]
        if postgis:
            args += ["--postgis-available-version", postgis]
        return run(*args)

    def test_restrict_dump_rejected_by_old_restore_client(self):
        path = self.write_profile(restrictCommands=True, pgDumpVersion="16.15")
        result = self.compat(path, restore="psql (PostgreSQL) 16.4")
        report = json.loads(result.stdout)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(report["verdict"], "FAIL")
        self.assertTrue(report["dumpEmitsRestrict"])
        self.assertTrue(any("\\restrict" in reason for reason in report["reasons"]))

    def test_restrict_dump_accepted_by_identified_16_10_client(self):
        path = self.write_profile(restrictCommands=True, pgDumpVersion="16.15",
                                  extensions=["postgis"])
        result = self.compat(path, restore="psql (PostgreSQL) 16.10 (Debian 16.10-1)",
                             server="16.4", postgis="3.4.2")
        report = json.loads(result.stdout)
        self.assertEqual((result.returncode, report["verdict"]), (0, "PASS"), result.stdout)

    def test_dump_version_alone_implies_restrict(self):
        path = self.write_profile(restrictCommands=False, pgDumpVersion="16.10")
        result = self.compat(path, restore="psql (PostgreSQL) 16.9")
        self.assertEqual(json.loads(result.stdout)["verdict"], "FAIL")

    def test_older_dump_without_restrict_may_use_older_client(self):
        path = self.write_profile(restrictCommands=False, pgDumpVersion="16.4",
                                  serverVersion="16.4")
        result = self.compat(path, restore="psql (PostgreSQL) 16.4", server="16.4")
        self.assertEqual(json.loads(result.stdout)["verdict"], "PASS")

    def test_major_mismatch_and_missing_postgis_fail(self):
        path = self.write_profile(pgDumpVersion="16.4", serverVersion="16.4",
                                  extensions=["postgis"])
        major = self.compat(path, restore="psql (PostgreSQL) 15.14", server="16.4")
        self.assertEqual(json.loads(major.stdout)["verdict"], "FAIL")
        missing = self.compat(path, restore="psql (PostgreSQL) 16.10", server="16.4")
        self.assertEqual(json.loads(missing.stdout)["verdict"], "FAIL")
        self.assertTrue(any("postgis" in reason for reason in json.loads(missing.stdout)["reasons"]))

    def test_does_not_offer_to_strip_restrict(self):
        path = self.write_profile(restrictCommands=True)
        text = self.compat(path, restore="psql (PostgreSQL) 16.4").stdout.decode()
        self.assertNotIn("strip", text.lower())
        self.assertNotIn("ignore", text.lower())


LEDGER_TOOL = ROOT / "scripts/lib/restore-erasure-ledger.py"
ERASED_BEFORE = "00000000-0000-4000-a000-0000000000b1"   # tombstoned before the data stamp
ERASED_AFTER = "00000000-0000-4000-a000-0000000000a1"    # present in data stamp, erased later
ERASED_MANUAL = "00000000-0000-4000-a000-0000000000c1"   # only in an operator supplement


def ledger_stamp(base, name, ids):
    stamp = Path(base) / name
    stamp.mkdir()
    (stamp / "backup-manifest.json").write_text(json.dumps({"timestamp": name}))
    (stamp / "erasure-tombstones.json").write_text(json.dumps(
        [{"authUserId": i, "erasedAt": "2026-09-01T00:00:00Z"} for i in ids]))
    return stamp


class ErasureLedgerTest(unittest.TestCase):
    """Deletion set through a recovery cutoff; synthetic identifiers only."""

    def setUp(self):
        self.work = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.work)
        self.data = ledger_stamp(self.work, "2026-09-20T03-30-01Z", [ERASED_BEFORE])
        self.newer = ledger_stamp(self.work, "2026-09-21T03-30-01Z", [ERASED_BEFORE, ERASED_AFTER])
        self.out = self.work / "merged.json"

    def build(self, *args):
        result = run(LEDGER_TOOL, "--data-stamp", self.data, "--out", self.out, *args)
        return result.returncode, json.loads(result.stdout), result.stdout.decode()

    def test_newer_ledger_covers_account_erased_after_backup(self):
        code, report, text = self.build("--ledger-stamp", self.newer,
                                        "--recovery-cutoff", "2026-09-21T03:30:01Z")
        self.assertEqual((code, report["verdict"]), (0, "PASS"), text)
        self.assertEqual(report["mergedTombstones"], 2)
        self.assertEqual(report["erasedAfterDataStamp"], 1)
        merged = {e["authUserId"] for e in json.loads(self.out.read_text())}
        self.assertEqual(merged, {ERASED_BEFORE, ERASED_AFTER})
        self.assertEqual(self.out.stat().st_mode & 0o777, 0o600)
        for identifier in (ERASED_BEFORE, ERASED_AFTER):
            self.assertNotIn(identifier, text)

    def test_bundled_ledger_alone_is_blocked_for_a_later_cutoff(self):
        code, report, _ = self.build("--recovery-cutoff", "2026-09-21T03:30:01Z")
        self.assertEqual((code, report["verdict"]), (3, "BLOCKED"))
        self.assertEqual(report["uncoveredSeconds"], 86400)
        self.assertEqual(report["coverageThrough"], "2026-09-20T03:30:01Z")

    def test_incident_after_newest_stamp_is_blocked_without_supplement(self):
        code, report, _ = self.build("--ledger-stamp", self.newer,
                                     "--recovery-cutoff", "2026-09-21T15:00:00Z")
        self.assertEqual((code, report["verdict"]), (3, "BLOCKED"))

    def test_supplement_closes_the_gap(self):
        supplement = self.work / "supplement.json"
        supplement.write_text(json.dumps([{"authUserId": ERASED_MANUAL}]))
        code, report, _ = self.build("--ledger-stamp", self.newer, "--supplemental", supplement,
                                     "--supplemental-covered-through", "2026-09-21T15:00:00Z",
                                     "--recovery-cutoff", "2026-09-21T15:00:00Z")
        self.assertEqual((code, report["verdict"]), (0, "PASS"))
        self.assertEqual((report["mergedTombstones"], report["supplementalTombstones"]), (3, 1))

    def test_supplement_needs_covered_through(self):
        supplement = self.work / "supplement.json"
        supplement.write_text("[]")
        code, report, _ = self.build("--supplemental", supplement,
                                     "--recovery-cutoff", "2026-09-20T03:30:01Z")
        self.assertEqual((code, report["verdict"]), (1, "FAIL"))

    def test_shrinking_ledger_history_fails(self):
        shrunk = ledger_stamp(self.work, "2026-09-22T03-30-01Z", [ERASED_AFTER])
        code, report, _ = self.build("--ledger-stamp", self.newer, "--ledger-stamp", shrunk,
                                     "--recovery-cutoff", "2026-09-22T03:30:01Z")
        self.assertEqual((code, report["verdict"]), (1, "FAIL"))
        self.assertFalse(self.out.exists())

    def test_non_uuid_identifier_fails(self):
        bad = ledger_stamp(self.work, "2026-09-22T03-30-01Z", [ERASED_BEFORE, "x'); DROP TABLE t;--"])
        code, report, _ = self.build("--ledger-stamp", bad, "--recovery-cutoff", "2026-09-22T03:30:01Z")
        self.assertEqual((code, report["verdict"]), (1, "FAIL"))


class ProcedureEarlyPhaseTest(unittest.TestCase):
    """restore-drill-01.sh stops before decryption; these phases need no Docker."""

    def setUp(self):
        self.work = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.work)
        self.stamp = make_stamp(self.work)

    def procedure(self, env_extra, cutoff):
        env = {"PATH": os.environ.get("PATH", ""), "PARKIO_DRILL_ID": "rd-unit-01", **env_extra}
        return subprocess.run(["bash", str(ROOT / "scripts/restore-drill-01.sh"), "--stamp", str(self.stamp),
                               "--recovery-cutoff", cutoff, "--container", "never-used",
                               "--work", str(self.work / "w"), "--evidence", str(self.work / "e")],
                              capture_output=True, env=env, check=False)

    def test_missing_passphrase_is_usage_error(self):
        self.assertEqual(self.procedure({}, "2026-09-20T03:30:01Z").returncode, 2)

    def test_stale_ledger_blocks_before_decryption(self):
        result = self.procedure({"BACKUP_ENCRYPT_PASSPHRASE": "synthetic"}, "2026-09-21T00:00:00Z")
        self.assertEqual(result.returncode, 3, result.stderr)
        summary = json.loads((self.work / "e/summary.json").read_text())
        self.assertEqual(summary["verdict"], "BLOCKED")
        self.assertFalse((self.work / "e/isolation.json").exists())
        self.assertFalse(list((self.work / "e").glob("*.profile.json")))


def closed_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class IsolationPreflightTest(unittest.TestCase):
    def setUp(self):
        self.work = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.work)
        self.containers = self.work / "containers.txt"
        self.containers.write_text("drill-postgres-auth\ndrill-postgres-parking\ndrill-minio\n")
        self.base_env = {
            "PATH": os.environ.get("PATH", ""),
            "PARKIO_DRILL_ID": "rd-20260923-a",
            "PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER": "1",
            "PARKIO_EMAIL_PROVIDER": "logging",
            "PARKIO_PUSH_DELIVERY_PROVIDER": "noop",
            "PARKIO_RESEND_API_KEY": "dummy-drill",
            "PARKIO_MUNICIPAL_IZUM_SCHEDULER_ENABLED": "false",
        }

    def preflight(self, env_overrides=None, *extra, hostname="drill-host-1"):
        env = dict(self.base_env, **(env_overrides or {}))
        env = {k: v for k, v in env.items() if v is not None}
        result = run(ISOLATION_TOOL, "--hostname", hostname, "--containers-file", self.containers,
                     "--probe-egress", f"127.0.0.1:{closed_port()}", "--timeout", "0.5", *extra,
                     env=env)
        return result.returncode, json.loads(result.stdout), result.stdout.decode()

    def test_isolated_inert_environment_passes(self):
        code, report, text = self.preflight()
        self.assertEqual(code, 0, text)
        self.assertEqual(report["verdict"], "PASS")

    def test_real_webhook_anywhere_fails_without_printing_it(self):
        webhook = "https://hooks.slack.com/services/T000/B000/XXXXXXXXXXXXXXXX"
        code, report, text = self.preflight({"SOME_UNRELATED_VAR": webhook})
        self.assertEqual((code, statuses(report)["credential-shapes"]), (1, "FAIL"))
        self.assertIn("SOME_UNRELATED_VAR", text)
        self.assertNotIn(webhook, text)

    def test_named_outbound_secret_fails(self):
        code, report, text = self.preflight({"PARKIO_AI_VISION_GEMINI_API_KEY": "live-value-123"})
        self.assertEqual((code, statuses(report)["outbound-credentials"]), (1, "FAIL"))
        self.assertNotIn("live-value-123", text)

    def test_offsite_credentials_must_be_gone_before_restore(self):
        code, report, _ = self.preflight({"BACKUP_AZURE_SAS_TOKEN": "sv=2024&sig=abc"})
        self.assertEqual((code, statuses(report)["outbound-credentials"]), (1, "FAIL"))

    def test_real_email_provider_fails(self):
        code, report, _ = self.preflight({"PARKIO_EMAIL_PROVIDER": "resend"})
        self.assertEqual((code, statuses(report)["inert-providers"]), (1, "FAIL"))

    def test_poller_or_sender_flag_fails(self):
        for flag in ("PARKIO_MUNICIPAL_IZUM_SCHEDULER_ENABLED", "PARKIO_MUNICIPAL_ENABLED",
                     "PARKIO_SLACK_BIZ_ENABLED", "BACKUP_PRODUCTION_MODE"):
            code, report, _ = self.preflight({flag: "true"})
            self.assertEqual((code, statuses(report)["senders-and-pollers-off"]), (1, "FAIL"), flag)

    def test_env_file_is_read(self):
        env_file = self.work / "drill.env"
        env_file.write_text("# drill\nexport PARKIO_WAITLIST_EMAIL_PROVIDER=resend\n")
        code, report, _ = self.preflight(None, "--env-file", env_file)
        self.assertEqual((code, statuses(report)["inert-providers"]), (1, "FAIL"))

    def test_erasure_ledger_requirement_and_drill_id(self):
        code, report, _ = self.preflight({"PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER": None,
                                          "PARKIO_DRILL_ID": None})
        self.assertEqual(code, 1)
        self.assertEqual(statuses(report)["erasure-ledger-required"], "FAIL")
        self.assertEqual(statuses(report)["drill-id"], "FAIL")

    def test_production_host_fails(self):
        code, report, _ = self.preflight(hostname="parkio-civo-prod")
        self.assertEqual((code, statuses(report)["host"]), (1, "FAIL"))

    def test_application_container_fails(self):
        self.containers.write_text("drill-postgres-auth\nparkio-notification-service\nslack-biz-worker\n")
        code, report, _ = self.preflight()
        self.assertEqual((code, statuses(report)["containers"]), (1, "FAIL"))

    def test_open_egress_fails(self):
        with socket.socket() as server:
            server.bind(("127.0.0.1", 0))
            server.listen(1)
            port = server.getsockname()[1]
            code, report, _ = self.preflight(None, "--probe-egress", f"127.0.0.1:{port}")
        self.assertEqual((code, statuses(report)["egress-blocked"]), (1, "FAIL"))


if __name__ == "__main__":
    unittest.main()
