#!/usr/bin/env python3
"""Evidence bundles and the trusted erasure set (U02 stage 4, isolated recovery).

Model/unit tests over the committed cross-language fixtures
(services/auth-service/src/test/resources/durable-erasure-evidence/v2/bundles), which
auth-service's TrustedErasureSet must reproduce. Synthetic keys and ids only.
"""
from __future__ import annotations

import base64
import copy
import importlib.util
import inspect
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts/lib"))

from recovery_evidence_contract import ContractError  # noqa: E402
from recovery_evidence_bundle import (  # noqa: E402
    BundleStore,
    bundle_content_digest,
    bundle_from_store,
    coverage_statement,
    encode_bundle,
    trusted_erasure_set,
    trusted_set_document,
)
from recovery_persist_protocol import FRONTIER_KEY, IsolatedVersionedStore  # noqa: E402

_spec = importlib.util.spec_from_file_location(
    "fixtures", ROOT / "scripts/generate-durable-erasure-evidence-fixtures.py")
fixtures = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(fixtures)

BUNDLES = fixtures.FIXTURE_DIR / "bundles"
# Phrases a coverage report must never contain: v2 evidence signs no time.
FORBIDDEN_CLAIMS = re.compile(
    r"\d{4}-\d{2}-\d{2}T|no later|absen|cutoff|complete coverage|all erasures|until now",
    re.IGNORECASE)


def committed(name):
    bundle = json.loads((BUNDLES / name / "bundle.json").read_text(encoding="utf-8"))
    expected = json.loads((BUNDLES / name / "expected.json").read_text(encoding="utf-8"))
    return bundle, expected


def outcome(bundle):
    try:
        return trusted_erasure_set(BundleStore(bundle), fixtures.STANDARD_TRUST, at=fixtures.VERIFIED_AT)
    except ContractError as error:
        return {"error": str(error)}


class TrustedErasureSetFixtureTest(unittest.TestCase):
    def test_every_committed_bundle_reaches_its_expected_set(self):
        names = json.loads((fixtures.FIXTURE_DIR / "bundles.json").read_text(encoding="utf-8"))
        self.assertGreaterEqual(len(names), 14)
        for name in names:
            with self.subTest(name=name):
                bundle, expected = committed(name)
                self.assertEqual(outcome(bundle), expected["result"])

    def test_the_set_is_the_latest_checkpoint_plus_the_pending_tail(self):
        _, expected = committed("checkpoint-tail")
        result = expected["result"]
        self.assertEqual(result["latestTrustedCheckpoint"], 3)
        self.assertEqual(result["verifiedThroughSequence"], 4)
        users = [entry["authUserId"] for entry in result["entries"]]
        self.assertEqual(users, sorted(users))
        self.assertEqual(set(users), {item["authUserId"] for item in fixtures.INPUTS}
                         | {fixtures.TAIL_INPUT["authUserId"]})

    def test_the_highest_verified_frontier_version_wins_in_any_listing_order(self):
        for name in ("frontier-versions-oldest-first", "frontier-versions-newest-first"):
            with self.subTest(name=name):
                result = committed(name)[1]["result"]
                self.assertEqual(result["verifiedThroughSequence"], 3)
                self.assertEqual(result["frontierVersion"], "v-0002")

    def test_an_unverifiable_newest_version_lowers_coverage_and_never_raises_it(self):
        """Older watermark: an older verified boundary bounds the set; later records stay out."""
        result = committed("frontier-newest-tampered")[1]["result"]
        self.assertEqual(result["verifiedThroughSequence"], 2)
        self.assertEqual(result["ignoredFrontierVersions"], 1)
        self.assertNotIn(fixtures.INPUTS[2]["authUserId"], [entry["authUserId"] for entry in result["entries"]])

    def test_listed_objects_above_the_frontier_are_never_trusted(self):
        """Listing maximum: completeness comes from the signed frontier, not the listing."""
        result = committed("listed-above-frontier")[1]["result"]
        self.assertEqual(result["verifiedThroughSequence"], 2)
        self.assertEqual(len(result["entries"]), 2)
        self.assertTrue(committed("missing-frontier")[1]["result"]["error"].startswith("UNKNOWN:"))

    def test_untrusted_evidence_yields_no_set(self):
        for name, prefix in (("gap", "BLOCKED:"), ("missing-frontier", "UNKNOWN:"),
                             ("tail-conflict", "conflicting erasedAt"),
                             ("below-checkpoint-missing", "a pending record below"),
                             ("frontier-all-tampered", "frontier signature mismatch"),
                             ("bundle-digest-mismatch", "evidence bundle digest mismatch"),
                             ("non-canonical-erased-at", "erasure set entry is not in the evidence format")):
            with self.subTest(name=name):
                self.assertTrue(committed(name)[1]["result"]["error"].startswith(prefix))

    def test_every_signed_object_is_read_only_under_its_own_key(self):
        """PR #295 B4: a re-keyed checkpoint or record, or a sequence with two owners, is refused."""
        for name, error in (("checkpoint-key-swap", "evidence object key does not match its body"),
                            ("record-under-another-key", "evidence object key does not match its body"),
                            ("duplicate-sequence", "duplicate sequence in published evidence"),
                            ("checkpoint-duplicate-user", "duplicate user in the checkpoint ledger")):
            with self.subTest(name=name):
                bundle, expected = committed(name)
                self.assertEqual(expected["result"], {"error": error})
                self.assertEqual(outcome(bundle), {"error": error})

    def test_the_swapped_checkpoint_would_have_dropped_a_tombstone(self):
        """Why B4 matters: read by key alone, the swap keeps 'verified through 4' and loses a user."""
        bundle, _ = committed("checkpoint-key-swap")
        swapped = json.loads(base64.b64decode(bundle["objects"]["checkpoints/0000000000000004.json"]))
        moved = json.loads(base64.b64decode(bundle["objects"]["checkpoints/0000000000000004-moved.json"]))
        self.assertEqual(swapped["sequence"], 3)
        self.assertEqual(moved["sequence"], 4)
        self.assertLess(len(swapped["entries"]), len(moved["entries"]))

    def test_a_frontier_version_missing_a_signed_field_is_ignored_and_counted(self):
        """PR #295 N6: as in auth-service, a missing signed field is a verification failure."""
        result = committed("frontier-version-missing-field")[1]["result"]
        self.assertEqual(result["verifiedThroughSequence"], 2)
        self.assertEqual(result["ignoredFrontierVersions"], 1)

    def test_there_is_no_cutoff_input_to_lower(self):
        """Lowered cutoff: no sequence or time cutoff can be supplied to widen coverage."""
        self.assertEqual(list(inspect.signature(trusted_erasure_set).parameters), ["store", "trust", "at"])
        self.assertNotIn("required", " ".join(inspect.signature(trusted_set_document).parameters))


class CoverageWordingTest(unittest.TestCase):
    def test_the_statement_names_only_the_verified_sequence_and_frontier_version(self):
        statement = coverage_statement(4, "v-0002")
        self.assertEqual(statement, "erasure coverage verified through sequence 4 (frontier version v-0002)")
        self.assertIsNone(FORBIDDEN_CLAIMS.search(statement))

    def test_no_committed_result_makes_a_time_or_absence_claim(self):
        for path in sorted(BUNDLES.glob("*/expected.json")) + [fixtures.FIXTURE_DIR / "trusted-set.json"]:
            with self.subTest(path=path.parent.name):
                document = json.loads(path.read_text(encoding="utf-8"))
                result = document.get("result", document.get("coverage", {}))
                if "statement" in result:
                    self.assertRegex(result["statement"],
                                     r"^erasure coverage verified through sequence \d+ \(frontier version [^)]+\)$")
                    self.assertIsNone(FORBIDDEN_CLAIMS.search(result["statement"]))


class BundleStoreTest(unittest.TestCase):
    def setUp(self):
        self.bundle, _ = committed("valid")

    def test_round_trip_from_a_model_store_is_deterministic(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = fixtures.valid_store(Path(tmp), "valid")
            self.assertEqual(bundle_from_store(store), self.bundle)

    def test_the_frontier_is_read_only_as_versions(self):
        store = BundleStore(self.bundle)
        with self.assertRaisesRegex(ContractError, "frontier is versioned"):
            store.get(FRONTIER_KEY)
        with self.assertRaisesRegex(ContractError, "frontier is versioned"):
            store.get_optional(FRONTIER_KEY)
        self.assertEqual(len(store.get_versions(FRONTIER_KEY)), 1)

    def test_malformed_bundles_are_refused(self):
        wrong_format = copy.deepcopy(self.bundle)
        wrong_format["format"] = "something-else"
        not_base64 = copy.deepcopy(self.bundle)
        key = sorted(not_base64["objects"])[0]
        not_base64["objects"][key] = "%%%"
        not_base64["bundleDigest"] = bundle_content_digest(not_base64)
        stale_digest = copy.deepcopy(self.bundle)
        stale_digest["source"] = "edited"
        cases = (
            (wrong_format, "unsupported evidence bundle format"),
            (stale_digest, "evidence bundle digest mismatch"),
            (encode_bundle({}, [("v1", b"{}"), ("v1", b"{}")], "test"), "repeats a frontier version id"),
            (encode_bundle({}, [("", b"{}")], "test"), "frontier version has no id"),
            (not_base64, "not base64"),
        )
        for bundle, message in cases:
            with self.subTest(message=message):
                with self.assertRaisesRegex(ContractError, message):
                    BundleStore(bundle)

    def test_only_evidence_keys_can_be_bundled(self):
        for key in ("../escape", "frontier/expected-through.json", "other/x.json", "records/../x"):
            with self.subTest(key=key):
                with self.assertRaisesRegex(ContractError, "not an evidence key"):
                    encode_bundle({key: b"x"}, [], "test")


class TrustedSetDocumentTest(unittest.TestCase):
    def test_the_committed_document_embeds_its_bundle_and_derived_set(self):
        document = json.loads((fixtures.FIXTURE_DIR / "trusted-set.json").read_text(encoding="utf-8"))
        derived = trusted_erasure_set(BundleStore(document["bundle"]), fixtures.STANDARD_TRUST,
                                      at=fixtures.VERIFIED_AT)
        self.assertEqual(document["erasureSet"]["erasureSetDigest"], derived["erasureSetDigest"])
        self.assertEqual(document["erasureSet"]["entries"], derived["entries"])
        self.assertEqual(document["coverage"]["statement"], derived["statement"])
        self.assertEqual(document["recoveryAttemptId"], fixtures.RECOVERY_ATTEMPT_ID)
        self.assertEqual(document["targetIdentity"], fixtures.TARGET_IDENTITY)

    def test_the_production_identity_is_never_a_target(self):
        bundle, _ = committed("valid")
        with self.assertRaisesRegex(ContractError, "production identity"):
            trusted_set_document(bundle, fixtures.STANDARD_TRUST, fixtures.RECOVERY_ATTEMPT_ID,
                                 fixtures.RESTORED_DATASET_ID, fixtures.DATABASE_IDENTITY,
                                 at=fixtures.VERIFIED_AT)

    def test_a_target_on_the_production_cluster_is_refused_whatever_its_name(self):
        """PR #295 B3: the shared identity cases; auth-service applies the same rule."""
        cases = json.loads((fixtures.FIXTURE_DIR / "database-identities.json").read_text(encoding="utf-8"))
        self.assertEqual(cases["productionIdentity"], fixtures.STANDARD_TRUST.database_identity)
        bundle, _ = committed("valid")
        for case in cases["cases"]:
            with self.subTest(name=case["name"]):
                try:
                    trusted_set_document(bundle, fixtures.STANDARD_TRUST, fixtures.RECOVERY_ATTEMPT_ID,
                                         fixtures.RESTORED_DATASET_ID, case["target"], at=fixtures.VERIFIED_AT)
                    refusal = None
                except ContractError as error:
                    refusal = str(error)
                self.assertEqual(refusal, case["refusal"])
        names = {case["name"]: case["refusal"] for case in cases["cases"]}
        self.assertIsNone(names["other-cluster"])
        self.assertIsNotNone(names["production-cluster-other-database"])
        self.assertIsNotNone(names["production-cluster-leading-zero"])

    def test_the_document_reports_ignored_frontier_versions(self):
        """PR #295 N1: tamper evidence travels with the set."""
        bundle, _ = committed("frontier-newest-tampered")
        document = trusted_set_document(bundle, fixtures.STANDARD_TRUST, fixtures.RECOVERY_ATTEMPT_ID,
                                        fixtures.RESTORED_DATASET_ID, fixtures.TARGET_IDENTITY,
                                        at=fixtures.VERIFIED_AT)
        self.assertEqual(document["coverage"]["ignoredFrontierVersions"], 1)
        committed_document = json.loads((fixtures.FIXTURE_DIR / "trusted-set.json").read_text(encoding="utf-8"))
        self.assertEqual(committed_document["coverage"]["ignoredFrontierVersions"], 0)

    def test_identifiers_are_required(self):
        bundle, _ = committed("valid")
        for attempt, dataset, target in (("", "d", "t"), ("a", " ", "t"), ("a", "d", None)):
            with self.subTest(attempt=attempt, dataset=dataset, target=target):
                with self.assertRaisesRegex(ContractError, "is required"):
                    trusted_set_document(bundle, fixtures.STANDARD_TRUST, attempt, dataset, target,
                                         at=fixtures.VERIFIED_AT)

    def test_untrusted_evidence_gives_no_document(self):
        bundle, _ = committed("gap")
        with self.assertRaisesRegex(ContractError, "^BLOCKED:"):
            trusted_set_document(bundle, fixtures.STANDARD_TRUST, fixtures.RECOVERY_ATTEMPT_ID,
                                 fixtures.RESTORED_DATASET_ID, fixtures.TARGET_IDENTITY,
                                 at=fixtures.VERIFIED_AT)


class DirectoryStoreParityTest(unittest.TestCase):
    def test_a_directory_store_still_reads_its_single_frontier(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = fixtures.valid_store(Path(tmp), "valid")
            self.assertIsInstance(store, IsolatedVersionedStore)
            result = trusted_erasure_set(store, fixtures.STANDARD_TRUST, at=fixtures.VERIFIED_AT)
            self.assertEqual(result, committed("valid")[1]["result"])


if __name__ == "__main__":
    unittest.main()
