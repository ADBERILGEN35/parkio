#!/usr/bin/env python3
"""Cross-language durable erasure evidence fixtures (format v1).

The committed fixtures under services/auth-service/src/test/resources/
durable-erasure-evidence/v1 are the contract between the Python persist protocol
(reference producer and verifier) and the Java producer and verifier in
auth-service. This file checks the Python side; DurableErasureEvidenceInteropTest
checks the Java side against the same bytes and verdicts.

Model/unit only: directory store, synthetic keys. Does not enable
verifiedCoverage, durable recording or production restore.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts/lib"))

from recovery_evidence_contract import ContractError, canonical_bytes, sign  # noqa: E402
from recovery_persist_protocol import (  # noqa: E402
    SIGNED_PENDING,
    IsolatedVersionedStore,
    signed_subset,
    verify_pending,
)

GENERATOR = ROOT / "scripts/generate-durable-erasure-evidence-fixtures.py"
_spec = importlib.util.spec_from_file_location("durable_fixture_generator", GENERATOR)
fixtures = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fixtures)


class DurableErasureEvidenceInteropTest(unittest.TestCase):
    def test_committed_fixtures_match_the_reference_producer(self):
        result = subprocess.run(
            [sys.executable, str(GENERATOR), "--check"],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_every_case_matches_its_expected_verdict(self):
        names = json.loads((fixtures.FIXTURE_DIR / "cases.json").read_text(encoding="utf-8"))
        self.assertGreaterEqual(len(names), 15)
        for name in names:
            case_dir = fixtures.FIXTURE_DIR / "cases" / name
            expected = json.loads((case_dir / "expected.json").read_text(encoding="utf-8"))
            with tempfile.TemporaryDirectory() as raw_tmp:
                # Read a copy so the committed fixture is never touched.
                store = IsolatedVersionedStore(Path(raw_tmp) / "store")
                for path in sorted((case_dir / "store").rglob("*")):
                    if path.is_file():
                        target = store.root / path.relative_to(case_dir / "store")
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(path.read_bytes())
                for check in expected["checks"]:
                    with self.subTest(case=name, required_through=check["requiredThrough"]):
                        actual = fixtures.evaluate(
                            store, expected["expectedDatabaseIdentity"],
                            expected["trustedProducers"], check["requiredThrough"],
                        )
                        self.assertEqual(actual, check)

    def test_cases_cover_the_acceptance_outcomes(self):
        outcomes = set()
        for name in json.loads((fixtures.FIXTURE_DIR / "cases.json").read_text(encoding="utf-8")):
            expected = json.loads(
                (fixtures.FIXTURE_DIR / "cases" / name / "expected.json").read_text(encoding="utf-8"))
            for check in expected["checks"]:
                outcomes.add(check.get("verdict") or check["error"])
        for outcome in ("ACCEPT_ISOLATED", "UNKNOWN", "BLOCKED", "producer signature mismatch",
                        "pending body digest mismatch", "database identity mismatch",
                        "unknown producer", "not a pending record", "frontier signature mismatch",
                        "frontier digest mismatch", "checkpoint ledger digest mismatch",
                        "missing records or unknown tail; recovery BLOCKED"):
            self.assertIn(outcome, outcomes)

    def test_canonical_json_cases_are_python_canonical_bytes(self):
        cases = json.loads((fixtures.FIXTURE_DIR / "canonical-json.json").read_text(encoding="utf-8"))
        for case in cases:
            with self.subTest(case=case["name"]):
                self.assertEqual(canonical_bytes(case["value"]).decode("ascii"), case["canonical"])

    def test_pre_interop_java_body_digest_is_rejected(self):
        """auth-service's DurableErasureRecord digest before format v1 hashed
        "requestId\\nuserId\\nerasedAt"; a record carrying it does not verify."""
        item = fixtures.INPUTS[0]
        legacy = hashlib.sha256(
            f"{item['erasureRequestId']}\n{item['authUserId']}\n{item['erasedAt']}".encode("utf-8")
        ).hexdigest()
        body = {
            "schemaVersion": 1,
            "kind": "erasure-pending-record",
            "erasureRecordId": f"records/{item['erasureRequestId']}.json",
            "erasureRequestId": item["erasureRequestId"],
            "authUserId": item["authUserId"],
            "erasedAt": item["erasedAt"],
            "sequence": 1,
            "databaseIdentity": fixtures.DATABASE_IDENTITY,
            "producerId": fixtures.PRODUCER_ID,
            "bodyDigest": legacy,
        }
        body["signature"] = sign(signed_subset(body, SIGNED_PENDING), fixtures.PRODUCER_KEY)
        with tempfile.TemporaryDirectory() as raw_tmp:
            store = IsolatedVersionedStore(Path(raw_tmp))
            store.put_if_absent(body["erasureRecordId"], canonical_bytes(body))
            with self.assertRaisesRegex(ContractError, "pending body digest mismatch"):
                verify_pending(store, body["erasureRecordId"], fixtures.DATABASE_IDENTITY,
                               {fixtures.PRODUCER_ID: fixtures.PRODUCER_KEY})


if __name__ == "__main__":
    unittest.main(verbosity=2)
