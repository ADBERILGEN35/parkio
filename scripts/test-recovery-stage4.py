#!/usr/bin/env python3
"""U02 stage 4 helpers: evidence verification before decrypt, and the expose gate.

Unit tests over the shared fixtures (durable-erasure-evidence/v2). The restore entrypoint itself
is exercised in scripts/test-restore-safe-preflight.sh. Synthetic keys and ids only.
"""
from __future__ import annotations

import copy
import importlib.util
import io
import json
import os
import re
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "services/auth-service/src/test/resources/durable-erasure-evidence/v2"
EVIDENCE_IDENTITY = "postgresql:7000000000000000001:parkio_auth"
TARGET = "postgresql:7000000000000000099:parkio_auth"
ATTEMPT = "5e5e5e5e-0000-4000-8000-00000000a773"
DATASET = "2026-09-20T03-30-01Z"
FORBIDDEN_CLAIMS = re.compile(r"\d{4}-\d{2}-\d{2}T\d|no later|absen|cutoff|all erasures", re.IGNORECASE)


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


evidence_cli = load("recovery_evidence_cli", "scripts/lib/recovery-evidence.py")
gate = load("recovery_expose_gate", "scripts/lib/recovery-expose-gate.py")


def trust_document(path, identity=EVIDENCE_IDENTITY):
    producer = json.loads((FIXTURES / "producer.json").read_text(encoding="utf-8"))
    key = next(k for k in producer["keys"] if k["keyId"] == "interop-fixture-key-2026a")
    Path(path).write_text(json.dumps({
        "format": "parkio-erasure-evidence-trust", "version": 1, "databaseIdentity": identity,
        "keys": [{"keyId": key["keyId"], "producerId": key["producerId"], "keyHex": key["keyHex"],
                  "notBefore": "2026-01-01T00:00:00Z"}],
    }), encoding="utf-8")
    return str(path)


def run(main, argv):
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        try:
            code = main(argv)
        except SystemExit as exit_:
            code = exit_.code
    return code, out.getvalue(), err.getvalue()


class VerifyBeforeDecryptTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.trust = trust_document(self.dir / "trust.json")
        self.out = self.dir / "trusted-erasure-set.json"

    def tearDown(self):
        self.tmp.cleanup()

    def verify(self, bundle="checkpoint-tail", attempt=ATTEMPT, target=TARGET, trust=None, out=None):
        return run(evidence_cli.main, [
            "verify", "--bundle", str(FIXTURES / "bundles" / bundle / "bundle.json"),
            "--trust", trust or self.trust, "--attempt", attempt, "--dataset", DATASET,
            "--target-identity", target, "--out", str(out or self.out)])

    def test_trusted_evidence_writes_a_private_trusted_set_and_states_only_the_sequence(self):
        code, stdout, _ = self.verify()

        self.assertEqual(code, 0)
        self.assertRegex(stdout.strip(),
                         r"^erasure coverage verified through sequence 4 \(frontier version sha256:[0-9a-f]{64}\)$")
        self.assertIsNone(FORBIDDEN_CLAIMS.search(stdout))
        document = json.loads(self.out.read_text(encoding="utf-8"))
        self.assertEqual(document["recoveryAttemptId"], ATTEMPT)
        self.assertEqual(document["restoredDatasetId"], DATASET)
        self.assertEqual(document["targetIdentity"], TARGET)
        self.assertEqual(self.out.stat().st_mode & 0o077, 0)

    def test_untrusted_evidence_is_blocked_and_writes_nothing(self):
        for bundle in ("gap", "missing-frontier", "tail-conflict", "frontier-all-tampered",
                       "bundle-digest-mismatch", "non-canonical-erased-at"):
            with self.subTest(bundle=bundle):
                code, _, stderr = self.verify(bundle=bundle)
                self.assertEqual(code, 3)
                self.assertTrue(stderr.startswith("BLOCKED: erasure evidence is not trusted: "))
                self.assertFalse(self.out.exists())

    def test_the_production_identity_is_never_a_target(self):
        code, _, stderr = self.verify(target=EVIDENCE_IDENTITY)

        self.assertEqual(code, 3)
        self.assertIn("production identity", stderr)
        self.assertFalse(self.out.exists())

    def test_usage_errors_exit_two(self):
        self.assertEqual(self.verify(attempt="not-a-uuid")[0], 2)
        self.assertEqual(self.verify(attempt=ATTEMPT.upper())[0], 2)
        self.assertEqual(self.verify(out=self.dir / "missing" / "set.json")[0], 2)
        self.assertEqual(run(evidence_cli.main, ["verify", "--bundle", "x"])[0], 2)

    def test_the_trust_secret_is_never_printed(self):
        key_hex = json.loads(Path(self.trust).read_text())["keys"][0]["keyHex"]
        for bundle in ("checkpoint-tail", "gap"):
            _, stdout, stderr = self.verify(bundle=bundle)
            self.assertNotIn(key_hex, stdout + stderr)


class ExposeGateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        trust = trust_document(self.dir / "trust.json")
        code, _, _ = run(evidence_cli.main, [
            "verify", "--bundle", str(FIXTURES / "bundles" / "checkpoint-tail" / "bundle.json"),
            "--trust", trust, "--attempt", ATTEMPT, "--dataset", DATASET, "--target-identity", TARGET,
            "--out", str(self.dir / "trusted-erasure-set.json")])
        assert code == 0
        self.trusted = json.loads((self.dir / "trusted-erasure-set.json").read_text(encoding="utf-8"))
        self.assertEqual(run(gate.main, ["init", "--recovery-dir", str(self.dir)])[0], 0)

    def tearDown(self):
        self.tmp.cleanup()

    def complete_verdict(self):
        participants = {name: {"success": 4, "failed": 0, "missing": 0}
                        for name in ("user", "parking", "media", "moderation", "gamification",
                                     "notification", "analytics", "ai-validation")}
        return {
            "format": "parkio-recovery-replay-verdict", "version": 1,
            "recoveryAttemptId": ATTEMPT, "restoredDatasetId": DATASET,
            "erasureSetDigest": self.trusted["erasureSet"]["erasureSetDigest"],
            "coverage": dict(self.trusted["coverage"]), "users": 4,
            "participants": participants, "auth": {"success": 4, "failed": 0, "missing": 0},
            "status": "COMPLETE", "exitCode": 0,
        }

    def open_with(self, verdict, operator="drill-operator"):
        path = self.dir / "verdict.json"
        if verdict is None:
            path.unlink(missing_ok=True)
        else:
            path.write_text(json.dumps(verdict), encoding="utf-8")
        return run(gate.main, ["open", "--recovery-dir", str(self.dir), "--verdict", str(path),
                               "--operator", operator])

    def state(self):
        return json.loads((self.dir / "expose-gate.json").read_text(encoding="utf-8"))["state"]

    def test_the_gate_starts_closed_and_status_says_so(self):
        self.assertEqual(self.state(), "CLOSED")
        self.assertEqual(run(gate.main, ["status", "--recovery-dir", str(self.dir)])[0], 3)

    def test_only_a_matching_complete_verdict_opens_it(self):
        code, stdout, _ = self.open_with(self.complete_verdict())

        self.assertEqual(code, 0)
        self.assertIn("no service started, no port published", stdout)
        written = json.loads((self.dir / "expose-gate.json").read_text(encoding="utf-8"))
        self.assertEqual(written["state"], "OPEN")
        self.assertEqual(written["operator"], "drill-operator")
        self.assertRegex(written["verdictSha256"], "^[0-9a-f]{64}$")
        self.assertEqual(run(gate.main, ["status", "--recovery-dir", str(self.dir)])[0], 0)

    def test_anything_else_leaves_it_closed(self):
        def edited(**changes):
            verdict = self.complete_verdict()
            for key, value in changes.items():
                verdict[key] = value
            return verdict

        missing_participant = self.complete_verdict()
        missing_participant["participants"]["media"]["missing"] = 1
        failed_participant = self.complete_verdict()
        failed_participant["participants"]["user"]["failed"] = 1
        missing_auth = self.complete_verdict()
        missing_auth["auth"]["missing"] = 4
        no_auth = self.complete_verdict()
        del no_auth["auth"]
        other_coverage = self.complete_verdict()
        other_coverage["coverage"]["statement"] = "erasure coverage verified through sequence 9 (frontier version v)"
        cases = {
            "BLOCKED": edited(status="BLOCKED", exitCode=24),
            "TIMEOUT": edited(status="TIMEOUT", exitCode=25),
            "COMPLETE with a nonzero exit": edited(exitCode=26),
            "another attempt": edited(recoveryAttemptId="5e5e5e5e-0000-4000-8000-0000000000ff"),
            "another dataset": edited(restoredDatasetId="another-stamp"),
            "another digest": edited(erasureSetDigest="0" * 64),
            "another coverage": other_coverage,
            "a participant missing": missing_participant,
            "a participant failed": failed_participant,
            "auth missing": missing_auth,
            "no auth count": no_auth,
            "not a verdict": edited(format="something-else"),
            "no verdict file": None,
        }
        for label, verdict in cases.items():
            with self.subTest(label=label):
                code, _, stderr = self.open_with(verdict)
                self.assertEqual(code, 3)
                self.assertIn("expose gate stays CLOSED", stderr)
                self.assertEqual(self.state(), "CLOSED")
        self.assertEqual(self.open_with(self.complete_verdict(), operator=" ")[0], 3)
        self.assertEqual(self.state(), "CLOSED")

    def test_a_gate_of_another_recovery_cannot_be_opened(self):
        trusted = copy.deepcopy(self.trusted)
        trusted["recoveryAttemptId"] = "5e5e5e5e-0000-4000-8000-0000000000ee"
        (self.dir / "trusted-erasure-set.json").write_text(json.dumps(trusted), encoding="utf-8")

        code, _, stderr = self.open_with(self.complete_verdict())

        self.assertEqual(code, 3)
        self.assertIn("another recovery", stderr)
        self.assertEqual(self.state(), "CLOSED")

    def test_a_new_restore_closes_an_open_gate(self):
        self.assertEqual(self.open_with(self.complete_verdict())[0], 0)

        self.assertEqual(run(gate.main, ["init", "--recovery-dir", str(self.dir)])[0], 0)

        self.assertEqual(self.state(), "CLOSED")
        self.assertEqual((self.dir / "expose-gate.json").stat().st_mode & 0o077, 0)


if __name__ == "__main__":
    unittest.main()
