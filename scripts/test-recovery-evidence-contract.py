#!/usr/bin/env python3
"""Isolated synthetic proof for the recovery evidence contract.

Classification:
- This file: model/unit tests (in-process table, directory store, HMAC fixture).
- scripts/test-recovery-evidence-pg-share-lock.py: real PostgreSQL locking.
- IsolatedObjectStore tests: directory fixture, not a real object-store service.
- scripts/test-restore-safe-preflight.sh: real restore-entrypoint with stubs.
  Replay here is modeled; it is not end-to-end production-entrypoint acceptance.

Disposable fixtures only. Does not download, decrypt, or restore production
backups. Does not enable verifiedCoverage.
"""
from __future__ import annotations

import calendar
import json
import re
import sys
import tempfile
import threading
import time
import types
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts/lib"))

import recovery_evidence_contract  # noqa: E402
from recovery_evidence_contract import (  # noqa: E402
    AUTH_ERASURE_SERVICE,
    AckRefused,
    CaptureAborted,
    CONFIGURED_PARTICIPANTS,
    ContractError,
    DurableAckGate,
    EvidenceConsumer,
    ExposeRefused,
    IsolatedAuthTable,
    IsolatedMediaBucket,
    IsolatedObjectStore,
    PersistFailed,
    PROTOCOL_LOCK,
    REQUIRED_PARTICIPANTS,
    ReplayCompletion,
    configured_participants_from_auth,
    isolated_replay,
    persist_before_ack_is_not_implemented,
    publish_snapshot,
    restore_erasure_ledger_still_unverified,
    verify_evidence,
    write_claim_file,
)

ERASED = "00000000-0000-4000-a000-0000000000a1"
UNRELATED = "00000000-0000-4000-a000-000000000099"
LATER = "00000000-0000-4000-a000-0000000000b2"
DB = "auth-db:isolated-fixture"
PRODUCER = "fixture-producer"
KEY = b"parkio-isolated-recovery-evidence-not-prod"
CUTOFF_OK = "2026-09-27T10:00:00Z"
CUTOFF_LATE = "2026-09-27T18:00:00Z"
ATTEMPT_1 = "recovery-attempt-1"
ATTEMPT_2 = "recovery-attempt-2"
DATASET_1 = "dataset-stamp-s"
DATASET_2 = "dataset-stamp-l"


class RecoveryEvidenceContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = IsolatedObjectStore(Path(self.tmp.name) / "store")
        self.table = IsolatedAuthTable(DB, clock=lambda: calendar.timegm((2026, 9, 27, 10, 0, 0, 0, 0, 0)))
        self.keys = {PRODUCER: KEY}

    def tearDown(self):
        self.tmp.cleanup()

    def _publish(self, store=None, table=None):
        snapshot = (table or self.table).capture()
        return publish_snapshot(store or self.store, snapshot, PRODUCER, KEY)

    def test_production_verified_coverage_stays_false(self):
        """model/unit: source guard on restore-erasure-ledger.py."""
        self.assertTrue(restore_erasure_ledger_still_unverified(ROOT))

    def test_persist_before_ack_is_not_implemented(self):
        """model/unit: current API returns IN_PROGRESS after auth TX, not persist-ACK."""
        self.assertTrue(persist_before_ack_is_not_implemented(ROOT))

    def test_configured_participants_match_auth_source(self):
        """model/unit: inventory is derived from auth DEFAULT_PARTICIPANTS."""
        self.assertEqual(configured_participants_from_auth(ROOT), CONFIGURED_PARTICIPANTS)
        self.assertEqual(REQUIRED_PARTICIPANTS[0], "auth")
        self.assertEqual(REQUIRED_PARTICIPANTS[1:], CONFIGURED_PARTICIPANTS)

    def test_claim_file_alone_is_rejected(self):
        """model/unit: claim file without store object is REJECT."""
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        receipt, body = self._publish()
        claim_path = Path(self.tmp.name) / "claim.json"
        claim = write_claim_file(claim_path, body, receipt["publicationId"])
        empty = IsolatedObjectStore(Path(self.tmp.name) / "empty")
        with self.assertRaises(ContractError):
            verify_evidence(empty, DB, self.keys, CUTOFF_OK, claim=claim)

    def test_store_backed_isolated_accept_does_not_certify_production(self):
        """model/unit: directory-store HMAC accept is ACCEPT_ISOLATED only."""
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        receipt, body = self._publish()
        verified = verify_evidence(
            self.store, DB, self.keys, CUTOFF_OK,
            publication_id=receipt["publicationId"],
        )
        self.assertEqual(verified["verdict"], "ACCEPT_ISOLATED")
        self.assertFalse(verified["verifiedCoverage"])
        self.assertFalse(verified["certifiedProduction"])
        self.assertEqual(verified["captureProtocol"], PROTOCOL_LOCK)
        self.assertIn(ERASED, verified["ids"])
        self.assertIn("producer-authenticity", verified["trustProved"])
        self.assertIn("completeness-through-boundary", verified["trustNotProved"])

    def test_concurrent_erasure_waits_for_share_lock_model(self):
        """model/unit: in-process SHARE simulation. Not a real PostgreSQL locking test."""
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        release = threading.Event()
        acquired = threading.Event()
        self.table.hold_share = release
        self.table.share_acquired = acquired
        captured = {}

        def run_capture():
            captured["snapshot"] = self.table.capture()

        worker = threading.Thread(target=run_capture)
        worker.start()
        self.assertTrue(acquired.wait(1))
        inserted = {"done": False}

        def run_insert():
            self.table.insert(LATER, "2026-09-27T10:00:01Z")
            inserted["done"] = True

        writer = threading.Thread(target=run_insert)
        writer.start()
        time.sleep(0.05)
        self.assertFalse(inserted["done"], "concurrent insert committed during SHARE")
        release.set()
        worker.join(2)
        writer.join(2)
        self.assertTrue(inserted["done"])
        ids = {item["authUserId"] for item in captured["snapshot"]["entries"]}
        self.assertIn(ERASED, ids)
        self.assertNotIn(LATER, ids)

    def test_capture_transaction_failure_publishes_nothing(self):
        """model/unit: aborted capture writes no store object."""
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        self.table.fail_next_capture = True
        with self.assertRaises(CaptureAborted):
            self.table.capture()
        self.assertEqual(self.store.put_attempts, 0)
        self.assertEqual(list(Path(self.store.root).rglob("*")), [])

    def test_offhost_persist_failure_does_not_ack(self):
        """model/unit: proposed DurableAckGate; directory store persist failure."""
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        failing = IsolatedObjectStore(Path(self.tmp.name) / "fail", fail_puts=1)
        snapshot = self.table.capture()
        with self.assertRaises(PersistFailed):
            publish_snapshot(failing, snapshot, PRODUCER, KEY)
        gate = DurableAckGate()
        with self.assertRaises(ContractError):
            verify_evidence(failing, DB, self.keys, CUTOFF_OK, publication_id="watermarks/missing.json")
        self.assertEqual(gate.status(ERASED)["status"], "ERASURE_PENDING_DURABLE")

    def test_missing_altered_and_wrong_database_evidence(self):
        """model/unit: missing object, tampered bytes, wrong expected DB."""
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        receipt, body = self._publish()
        with self.assertRaises(ContractError):
            verify_evidence(self.store, DB, self.keys, CUTOFF_OK, publication_id="watermarks/absent.json")
        raw = self.store.get(receipt["publicationId"])
        tampered = json.loads(raw)
        tampered["ledgerDigest"] = "0" * 64
        self.store._path(receipt["publicationId"]).write_bytes(
            json.dumps(tampered, separators=(",", ":"), sort_keys=True).encode()
        )
        with self.assertRaises(ContractError):
            verify_evidence(self.store, DB, self.keys, CUTOFF_OK, publication_id=receipt["publicationId"])
        self.store.put(receipt["publicationId"], raw)
        with self.assertRaises(ContractError):
            verify_evidence(self.store, "auth-db:other-cluster", self.keys, CUTOFF_OK,
                            publication_id=receipt["publicationId"])

    def test_erasure_after_watermark_then_host_loss_does_not_lower_cutoff(self):
        """model/unit: unknown tail stays BLOCKED; older watermark cannot authorize expose."""
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        receipt, _body = self._publish()
        verified = verify_evidence(
            self.store, DB, self.keys, "2026-09-27T10:00:00Z",
            publication_id=receipt["publicationId"],
        )
        gate = DurableAckGate()
        self.assertEqual(gate.acknowledge(ERASED, verified)["status"], "ACK")
        self.table.insert(LATER, "2026-09-27T11:00:00Z")
        lost_table = IsolatedAuthTable(DB)
        self.assertNotIn(LATER, lost_table.committed_ids())
        with self.assertRaises(ContractError) as ctx:
            verify_evidence(
                self.store, DB, self.keys, CUTOFF_LATE,
                publication_id=receipt["publicationId"],
            )
        self.assertIn("do not lower the cutoff", str(ctx.exception))
        self.assertEqual(gate.status(LATER)["status"], "ERASURE_PENDING_DURABLE")
        with self.assertRaises(AckRefused):
            gate.acknowledge(LATER, verified)

    def test_unknown_missing_tail_does_not_preserve_unacked_request(self):
        """model/unit: calling a lost request unacknowledged does not recreate it."""
        self.table.insert(LATER, "2026-09-27T11:00:00Z")
        lost = IsolatedAuthTable(DB)
        self.assertNotIn(LATER, lost.committed_ids())
        gate = DurableAckGate()
        self.assertEqual(gate.status(LATER)["status"], "ERASURE_PENDING_DURABLE")
        self.assertIsNone(gate._acks.get(LATER))

    def test_hmac_and_receipt_do_not_prove_completeness(self):
        """model/unit: a signed incomplete snapshot still ACCEPT_ISOLATED."""
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        self.table.insert(LATER, "2026-09-27T09:30:00Z")
        incomplete = {
            "captureProtocol": PROTOCOL_LOCK,
            "databaseIdentity": DB,
            "coveredThrough": CUTOFF_OK,
            "entries": [{"authUserId": ERASED, "erasedAt": "2026-09-27T09:00:00Z"}],
        }
        receipt, _body = publish_snapshot(self.store, incomplete, PRODUCER, KEY)
        verified = verify_evidence(
            self.store, DB, self.keys, CUTOFF_OK,
            publication_id=receipt["publicationId"],
        )
        self.assertEqual(verified["verdict"], "ACCEPT_ISOLATED")
        self.assertFalse(verified["verifiedCoverage"])
        self.assertIn(ERASED, verified["ids"])
        self.assertNotIn(LATER, verified["ids"])
        self.assertIn("completeness-through-boundary", verified["trustNotProved"])

    def test_older_valid_evidence_is_rejected_for_freshness(self):
        """model/unit: older valid HMAC object cannot replace a newer accepted watermark."""
        early = IsolatedAuthTable(DB, clock=lambda: calendar.timegm((2026, 9, 27, 9, 0, 0, 0, 0, 0)))
        early.insert(ERASED, "2026-09-27T08:00:00Z")
        old_receipt, _ = publish_snapshot(self.store, early.capture(), PRODUCER, KEY)
        late = IsolatedAuthTable(DB, clock=lambda: calendar.timegm((2026, 9, 27, 10, 0, 0, 0, 0, 0)))
        late.insert(ERASED, "2026-09-27T08:00:00Z")
        late.insert(LATER, "2026-09-27T09:30:00Z")
        new_receipt, _ = publish_snapshot(self.store, late.capture(), PRODUCER, KEY)
        consumer = EvidenceConsumer()
        consumer.accept(self.store, DB, self.keys, CUTOFF_OK, publication_id=new_receipt["publicationId"])
        with self.assertRaises(ContractError) as ctx:
            consumer.accept(self.store, DB, self.keys, "2026-09-27T09:00:00Z",
                            publication_id=old_receipt["publicationId"])
        self.assertIn("freshness failed", str(ctx.exception))

    def test_rotated_key_rejects_old_producer_and_retired_key_still_verifies(self):
        """model/unit: keys are trusted independently; rotation without not-before still verifies old objects."""
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        receipt, _ = self._publish()
        with self.assertRaises(ContractError):
            verify_evidence(self.store, DB, {PRODUCER: b"rotated-key-not-prod"}, CUTOFF_OK,
                            publication_id=receipt["publicationId"])
        both = {PRODUCER: KEY, "fixture-producer-v2": b"rotated-key-not-prod"}
        verified = verify_evidence(self.store, DB, both, CUTOFF_OK,
                                   publication_id=receipt["publicationId"])
        self.assertEqual(verified["verdict"], "ACCEPT_ISOLATED")
        self.assertFalse(verified["verifiedCoverage"])

    def test_missing_participant_ack_refuses_expose(self):
        """model/unit: eight configured participants plus auth are required."""
        completion = ReplayCompletion(ATTEMPT_1, DATASET_1, "digest-1")
        for name in ("auth", "user", "parking", "media"):
            completion.ack(name, ATTEMPT_1, DATASET_1, "digest-1")
        with self.assertRaises(ExposeRefused) as ctx:
            completion.expose()
        self.assertIn("moderation", str(ctx.exception))

    def test_prior_recovery_attempt_acks_cannot_authorize_new_restore(self):
        """model/unit: ACKs from an earlier attempt/dataset cannot complete a new restore."""
        current = ReplayCompletion(ATTEMPT_2, DATASET_2, "digest-new")
        with self.assertRaises(ExposeRefused):
            current.ack("auth", ATTEMPT_1, DATASET_2, "digest-new")
        with self.assertRaises(ExposeRefused):
            current.ack("user", ATTEMPT_2, DATASET_1, "digest-new")
        with self.assertRaises(ExposeRefused):
            current.ack("parking", ATTEMPT_2, DATASET_2, "digest-old")
        for name in REQUIRED_PARTICIPANTS:
            current.ack(name, ATTEMPT_2, DATASET_2, "digest-new")
        self.assertEqual(current.expose()["replayComplete"], True)

    def test_successful_isolated_replay_preserves_unrelated(self):
        """model/unit: isolated replay model, not production-entrypoint acceptance."""
        auth_status = {ERASED: "ACTIVE", UNRELATED: "ACTIVE"}
        media = IsolatedMediaBucket()
        media.put("media/erased.jpg", ERASED)
        media.put("media/keep.jpg", UNRELATED)
        result = isolated_replay(
            auth_status, media, [ERASED], UNRELATED, "digest-ok", ATTEMPT_1, DATASET_1,
        )
        self.assertTrue(result["erasedAbsent"])
        self.assertTrue(result["unrelatedPreserved"])
        self.assertEqual(result["publicTraffic"], "not-enabled")
        self.assertEqual(auth_status[ERASED], "ERASED")
        self.assertEqual(auth_status[UNRELATED], "ACTIVE")
        self.assertNotIn("media/erased.jpg", media.objects)
        self.assertIn("media/keep.jpg", media.objects)


IN_PROGRESS = 'return new AccountDeletionStatusView(requestId, "IN_PROGRESS");'
DELEGATING = """\
    @Transactional
    public AccountDeletionStatusView requestDeletion(UUID principalUserId, String password) {
        return requestDeletion(principalUserId, password, null);
    }
"""
IMPLEMENTING = f"""\
    @Transactional
    public AccountDeletionStatusView requestDeletion(UUID principalUserId, String password, String clientKey) {{
        verifyPassword(principalUserId, password, clientKey);
        if (principalUserId == null) {{
            throw new IllegalArgumentException("principal");
        }}
        UUID requestId = UUID.randomUUID();
        log.info("erasure requested requestId={{}} status=IN_PROGRESS", requestId);
        {IN_PROGRESS}
    }}
"""


class PersistBeforeAckScanTest(unittest.TestCase):
    """model/unit: the requestDeletion source guard on synthetic auth sources, both overload orders."""

    # The scanner under test. ScannerMutationTest runs this class against mutated copies of it.
    contract = recovery_evidence_contract

    def scan(self, *methods, header=""):
        """``header`` goes between the package line and the class: imports, the class Javadoc."""
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / AUTH_ERASURE_SERVICE
            path.parent.mkdir(parents=True)
            path.write_text(
                "package com.parkio.auth.application;\n\n" + header + "class AccountErasureApplicationService {\n\n"
                + "\n".join(methods) + "}\n",
                encoding="utf-8",
            )
            return self.contract.persist_before_ack_is_not_implemented(raw)

    def assert_both_orders_raise(self, pattern, delegating, implementing):
        for order, methods in (("delegation first", (delegating, implementing)),
                               ("implementation first", (implementing, delegating))):
            with self.subTest(order=order), self.assertRaisesRegex(self.contract.ContractError, pattern):
                self.scan(*methods)

    def test_one_implementation_and_pure_delegations_pass(self):
        self.assertTrue(self.scan(IMPLEMENTING))
        self.assertTrue(self.scan(DELEGATING, IMPLEMENTING))
        self.assertTrue(self.scan(IMPLEMENTING, DELEGATING))
        # Braces in literals and comments do not end a body early.
        tricky = IMPLEMENTING.replace(
            "        UUID requestId",
            "        char open = '{'; // }\n        String close = \"}\"; /* { */\n        UUID requestId")
        self.assertTrue(self.scan(DELEGATING, tricky))
        self.assertTrue(self.scan(tricky, DELEGATING))

    def test_implementation_without_in_progress_return_fails(self):
        complete = 'return new AccountDeletionStatusView(requestId, "COMPLETE");'
        for implementing in (IMPLEMENTING.replace(IN_PROGRESS, complete),
                             IMPLEMENTING.replace(IN_PROGRESS, f"// {IN_PROGRESS}\n        {complete}")):
            self.assert_both_orders_raise("no longer returns IN_PROGRESS", DELEGATING, implementing)

    def test_overload_that_does_work_fails(self):
        working = DELEGATING.replace(
            "        return requestDeletion", "        audit(principalUserId);\n        return requestDeletion")
        computed = DELEGATING.replace("password, null)", "password, resolveClient())")
        trailing = DELEGATING.replace("password, null);", "password, null); audit(principalUserId);")
        for delegating in (working, computed, trailing):
            self.assert_both_orders_raise("does work instead of delegating", delegating, IMPLEMENTING)

    def test_second_implementation_fails(self):
        second = IMPLEMENTING.replace("String clientKey)", "String clientKey, boolean retry)")
        self.assert_both_orders_raise("more than one", second, IMPLEMENTING)

    def test_persist_ack_vocabulary_in_any_overload_fails(self):
        for needle in ("offhost", "watermark", "persist-ack", "publicationId"):
            noted_delegation = DELEGATING.replace("        return", f"        // {needle}\n        return")
            noted_implementation = IMPLEMENTING.replace(
                "        UUID requestId", f"        // {needle}\n        UUID requestId")
            expected = f"requestDeletion unexpectedly mentions {needle.lower()}"
            with self.subTest(needle=needle):
                self.assert_both_orders_raise(expected, noted_delegation, IMPLEMENTING)
                self.assert_both_orders_raise(expected, DELEGATING, noted_implementation)

    def test_missing_method_fails(self):
        renamed = IMPLEMENTING.replace("requestDeletion(UUID principalUserId, String password, String",
                                       "startDeletion(UUID principalUserId, String password, String")
        commented = "    // public AccountDeletionStatusView requestDeletion(UUID id) { return null; }\n"
        for methods in ((), (renamed,), (commented,)):
            with self.subTest(methods=len(methods)), \
                    self.assertRaisesRegex(self.contract.ContractError, "requestDeletion missing"):
                self.scan(*methods)

    def test_each_overload_is_cut_at_its_own_closing_brace(self):
        """Scanner level: nested blocks and literal braces do not move the end of a body."""
        char_brace = IMPLEMENTING.replace("        UUID requestId", "        char open = '{';\n        UUID requestId")
        for methods in ((IMPLEMENTING, DELEGATING), (DELEGATING, IMPLEMENTING), (char_brace, DELEGATING)):
            text = "class AccountErasureApplicationService {\n" + "\n".join(methods) + "}\n"
            expected = [method[method.index("public"):method.rindex("}") + 1] for method in methods]
            with self.subTest(first=methods[0].splitlines()[1].strip()[:60]):
                self.assertEqual([source for source, _ in self.contract._request_deletion_overloads(text)], expected)

    def test_literal_braces_do_not_hide_an_overload_that_does_work(self):
        """A brace in a char literal or text block of the first overload must not swallow the next one."""
        working = DELEGATING.replace(
            "        return requestDeletion", "        offloadToWorker(principalUserId);\n        return requestDeletion")
        literals = {
            "char {": "char open = '{';",
            "char }": "char close = '}';",
            "text block {": 'String json = """\n            {\n            """;',
            "text block }": 'String json = """\n            }\n            """;',
            # #613: a { that only the block-comment branch, or only the escape handling, keeps out of the code.
            "block comment {": "/* { */",
            "string with an escaped quote before {": 'String quoted = "\\"{";',
            "char with an escaped quote before {": "char quote = '\\'', open = '{';",
        }
        for name, literal in literals.items():
            implementing = IMPLEMENTING.replace("        UUID requestId", f"        {literal}\n        UUID requestId")
            with self.subTest(literal=name), \
                    self.assertRaisesRegex(self.contract.ContractError, "does work instead of delegating"):
                self.scan(implementing, working)

    def test_persist_ack_vocabulary_anywhere_in_the_file_fails(self):
        """The needles are checked over the whole file, not only over the public overloads."""
        placements = {
            "helper after the method": ("    private void forgetLoginThrottleAfterCommit(String email) {{\n"
                                        "        // {needle}\n        loginFailures.forget(email);\n    }}\n"),
            "private overload": ("    private AccountDeletionStatusView requestDeletion(UUID principalUserId) {{\n"
                                 "        // {needle}\n        return requestDeletion(principalUserId, null, null);\n"
                                 "    }}\n"),
            "final overload": ("    public final AccountDeletionStatusView requestDeletion(String email) {{\n"
                               "        // {needle}\n        return null;\n    }}\n"),
            "Optional overload": ("    public Optional<AccountDeletionStatusView> requestDeletion(UUID id, boolean dry) {{\n"
                                  "        // {needle}\n        return Optional.empty();\n    }}\n"),
        }
        for needle in ("offhost", "watermark", "persist-ack", "publicationId"):
            for name, template in placements.items():
                extra = template.format(needle=needle)
                for methods in ((IMPLEMENTING, extra), (DELEGATING, IMPLEMENTING, extra)):
                    with self.subTest(needle=needle, placement=name, overloads=len(methods) - 1), \
                            self.assertRaisesRegex(self.contract.ContractError, f"mentions {needle.lower()}"):
                        self.scan(*methods)

    def test_persist_ack_vocabulary_before_the_first_overload_fails(self):
        """#613: a needle above the first overload counts as well: in an import, a field or the class Javadoc.

        A file-wide check that starts at the first requestDeletion misses each of these.
        """
        # An import names a type, so it cannot spell persist-ack.
        imports = {
            "offhost": "import com.parkio.auth.application.durable.OffHostErasureStore;\n\n",
            "watermark": "import com.parkio.auth.application.durable.CheckpointWatermark;\n\n",
            "publicationId": "import com.parkio.auth.application.durable.PublicationIdCodec;\n\n",
        }
        fields = {
            "offhost": "    private OffHostErasureStore offHostStore;\n",
            "watermark": "    private CheckpointWatermark watermark;\n",
            "persist-ack": '    private static final String ACK_MODE = "persist-ack";\n',
            "publicationId": "    private String publicationId;\n",
        }
        javadoc = "/** Erasure requests. The ACK follows the auth commit, with no {needle}. */\n"
        cases = [("import", needle, header, ()) for needle, header in imports.items()]
        cases += [("field", needle, "", (field,)) for needle, field in fields.items()]
        cases += [("class Javadoc", needle, javadoc.format(needle=needle), ())
                  for needle in ("offhost", "watermark", "persist-ack", "publicationId")]
        for placement, needle, header, members in cases:
            expected = re.escape(f"AccountErasureApplicationService.java unexpectedly mentions {needle.lower()}")
            for overloads in ((IMPLEMENTING,), (DELEGATING, IMPLEMENTING)):
                with self.subTest(placement=placement, needle=needle, overloads=len(overloads)), \
                        self.assertRaisesRegex(self.contract.ContractError, expected):
                    self.scan(*members, *overloads, header=header)

    def test_annotation_array_in_the_parameters_is_not_the_body(self):
        annotated = "@Size(groups = {Default.class}) String password"
        implementing = IMPLEMENTING.replace("String password, String clientKey)", f"{annotated}, String clientKey)")
        delegating = DELEGATING.replace("String password)", f"{annotated})")
        for index, methods in enumerate(((implementing,), (DELEGATING, implementing), (implementing, DELEGATING),
                                         (delegating, IMPLEMENTING), (IMPLEMENTING, delegating))):
            with self.subTest(case=index):
                self.assertTrue(self.scan(*methods))

    def test_escaped_triple_quote_does_not_end_a_text_block(self):
        block = 'String json = """\n            say \\""" and {\n            """;'
        implementing = IMPLEMENTING.replace("        UUID requestId", f"        {block}\n        UUID requestId")
        self.assertTrue(self.scan(implementing, DELEGATING))
        self.assertTrue(self.scan(DELEGATING, implementing))


SCANNER = ROOT / "scripts/lib/recovery_evidence_contract.py"
TEXT_BLOCK_LOOP = r'''            while end < length and not text.startswith('"""', end):'''
STRING_LOOP = r'''            while end < length and text[end] not in (text[index], "\n"):'''
ESCAPE_STEP = r'''                end += 2 if text[end] == "\\" else 1'''
NO_ESCAPE_STEP = "                end += 1"


def _lines(*lines):
    return "".join(line + "\n" for line in lines)


# The scanner's mutation set: the mutants of #572 and its reviews, the three that survived them (#613)
# among them. Each one weakens one path of the requestDeletion scanner and must fail the named
# PersistBeforeAckScanTest test. Its fragment must occur exactly once in the scanner; when a scanner
# change moves one, re-anchor the mutant on the new code instead of dropping it.
SCANNER_MUTANTS = (
    # (mutant, fragment of recovery_evidence_contract.py, replacement, the test it must fail)
    ("no exactly-one check",
     "    if len(implementing) > 1:\n", "    if False:\n",
     "test_second_implementation_fails"),
    ("no delegation check",
     "        if IN_PROGRESS_RETURN not in code and not PURE_DELEGATION.fullmatch(code):\n",
     "        if False:\n",
     "test_overload_that_does_work_fails"),
    ("match in place of fullmatch",
     "PURE_DELEGATION.fullmatch(code)", "PURE_DELEGATION.match(code)",
     "test_overload_that_does_work_fails"),
    ("IN_PROGRESS found in comments",
     "implementing = [code for _, code in overloads if IN_PROGRESS_RETURN in code]",
     "implementing = [code for source, code in overloads if IN_PROGRESS_RETURN in source]",
     "test_implementation_without_in_progress_return_fails"),
    ("no per-overload needle check",
     "    for source, _ in overloads:\n        lowered = source.lower()\n",
     "    for source, _ in ():\n        lowered = source.lower()\n",
     "test_persist_ack_vocabulary_in_any_overload_fails"),
    ("no file-wide needle check",
     "    lowered = text.lower()\n", '    lowered = ""\n',
     "test_persist_ack_vocabulary_anywhere_in_the_file_fails"),
    ("file-wide needle check from the first requestDeletion on (#613)",
     "    lowered = text.lower()\n", '    lowered = text[text.find("requestDeletion"):].lower()\n',
     "test_persist_ack_vocabulary_before_the_first_overload_fails"),
    ("line comments not classified",
     '        if text.startswith("//", index):\n', "        if False:\n",
     "test_missing_method_fails"),
    ("block comments not classified (#613)",
     '        elif text.startswith("/*", index):\n', "        elif False:\n",
     "test_literal_braces_do_not_hide_an_overload_that_does_work"),
    ("no text-block classification",
     "        elif text.startswith('\"\"\"', index):\n", "        elif False:\n",
     "test_literal_braces_do_not_hide_an_overload_that_does_work"),
    ("no text-block escape handling",
     _lines(TEXT_BLOCK_LOOP, ESCAPE_STEP), _lines(TEXT_BLOCK_LOOP, NO_ESCAPE_STEP),
     "test_escaped_triple_quote_does_not_end_a_text_block"),
    ("no char-literal classification",
     "        elif text[index] in \"\\\"'\":\n", "        elif text[index] == '\"':\n",
     "test_literal_braces_do_not_hide_an_overload_that_does_work"),
    ("no escape handling in strings and chars (#613)",
     _lines(STRING_LOOP, ESCAPE_STEP), _lines(STRING_LOOP, NO_ESCAPE_STEP),
     "test_literal_braces_do_not_hide_an_overload_that_does_work"),
    ("no brace-depth counting",
     "            if depth == 0:\n                closing = i\n",
     '            if text[i] == "}":\n                closing = i\n',
     "test_each_overload_is_cut_at_its_own_closing_brace"),
    ("no parameter-list skip",
     "range(parameters_end + 1, len(text))", "range(match.end(), len(text))",
     "test_annotation_array_in_the_parameters_is_not_the_body"),
    ("commented declarations counted",
     "if start < after or kinds[start] != _CODE:", "if start < after:",
     "test_missing_method_fails"),
)


class ScannerMutationTest(unittest.TestCase):
    """model/unit: PersistBeforeAckScanTest kills every mutant in the scanner's mutation set."""

    def load(self, source):
        """The scanner module built from ``source``, apart from the imported one."""
        module = types.ModuleType("recovery_evidence_contract_mutant")
        module.__file__ = str(SCANNER)
        exec(compile(source, str(SCANNER), "exec"), module.__dict__)
        return module

    def failed_tests(self, module):
        """Run PersistBeforeAckScanTest against ``module``: the tests run and the names of those that failed."""
        case = type("MutatedScannerTest", (PersistBeforeAckScanTest,), {"contract": module})
        result = unittest.TestResult()
        unittest.defaultTestLoader.loadTestsFromTestCase(case).run(result)
        failed = {getattr(test, "test_case", test)._testMethodName for test, _ in result.failures + result.errors}
        return result.testsRun, failed

    def test_the_unmutated_scanner_passes_through_the_same_harness(self):
        """A mutant counts as killed only because of its change, not because of how it is loaded."""
        run, failed = self.failed_tests(self.load(SCANNER.read_text(encoding="utf-8")))
        self.assertEqual(failed, set())
        self.assertEqual(run, unittest.defaultTestLoader.loadTestsFromTestCase(PersistBeforeAckScanTest).countTestCases())

    def test_every_mutant_fails_its_test(self):
        source = SCANNER.read_text(encoding="utf-8")
        for name, fragment, replacement, test in SCANNER_MUTANTS:
            with self.subTest(mutant=name):
                self.assertEqual(source.count(fragment), 1, f"{fragment!r} no longer anchors the mutant")
                _, failed = self.failed_tests(self.load(source.replace(fragment, replacement)))
                self.assertIn(test, failed)


if __name__ == "__main__":
    unittest.main()