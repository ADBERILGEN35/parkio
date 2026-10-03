#!/usr/bin/env python3
"""Generate the cross-language durable erasure evidence fixtures (format v2).

The Python persist protocol (scripts/lib/recovery_persist_protocol.py) is the
reference producer. This script runs it against a throwaway directory store and
writes deterministic store snapshots plus expected verdicts. The Java producer and
verifier in auth-service (com.parkio.auth.application.durable) are tested against
the same files: Java must write byte-identical records, markers, frontier and
checkpoint, both verifiers must reach the same verdict or error for every case under the
case's trust (keys, windows, retirement, pinned database identity) and verification instant,
and both trust-document loaders must accept or refuse the same documents with the same message.

Usage:
  scripts/generate-durable-erasure-evidence-fixtures.py            # rewrite fixtures
  scripts/generate-durable-erasure-evidence-fixtures.py --check    # fail on drift

The HMAC keys below are synthetic test values, not secrets.
"""
from __future__ import annotations

import argparse
import filecmp
import json
import shutil
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts/lib"))

from recovery_evidence_contract import ContractError, PersistFailed, canonical_bytes, sign  # noqa: E402
from recovery_persist_protocol import (  # noqa: E402
    CHECKPOINT_RESERVATION_ID,
    FRONTIER_KEY,
    KIND_FRONTIER,
    SCHEMA_VERSION,
    SIGNED_FRONTIER,
    SIGNED_PENDING,
    EvidenceTrust,
    IsolatedErasureCoordinator,
    IsolatedVersionedStore,
    SequenceAllocator,
    checkpoint_key,
    erasure_record_id,
    frontier_digest,
    recover_latest_trusted,
    signed_subset,
    TrustedKey,
)

FIXTURE_DIR = ROOT / "services/auth-service/src/test/resources/durable-erasure-evidence/v2"
# postgresql:<system_identifier>:<datname>, synthetic values.
DATABASE_IDENTITY = "postgresql:7000000000000000001:parkio_auth"
OTHER_DATABASE_IDENTITY = "postgresql:7000000000000000002:parkio_auth"
PRODUCER_ID = "interop-fixture-producer"
OTHER_PRODUCER_ID = "interop-other-producer"
KEY_ID = "interop-fixture-key-2026a"
ROTATED_KEY_ID = "interop-fixture-key-2026b"
OTHER_KEY_ID = "interop-other-key-2026a"
KEYS = {
    KEY_ID: {"producerId": PRODUCER_ID, "key": b"parkio-durable-interop-fixture-not-a-secret"},
    ROTATED_KEY_ID: {"producerId": PRODUCER_ID, "key": b"parkio-durable-interop-rotated-not-a-secret"},
    OTHER_KEY_ID: {"producerId": OTHER_PRODUCER_ID, "key": b"parkio-durable-interop-other-not-a-secret"},
}
KEY_NOT_BEFORE = "2026-01-01T00:00:00Z"
SIGNED_AT = "2026-09-29T08:20:00Z"
ROTATION_AT = "2026-09-30T00:00:00Z"
ROTATED_SIGNED_AT = "2026-09-30T08:00:00Z"
BEFORE_ROTATION = "2026-09-29T12:00:00Z"
VERIFIED_AT = "2026-10-01T00:00:00Z"

# erasedAt values use java.time.Instant#toString after truncation to microseconds:
# no fraction for whole seconds, otherwise 3 or 6 digits.
INPUTS = (
    {
        "erasureRequestId": "a1b2c3d4-0000-4000-8000-000000000001",
        "authUserId": "0f0e0d0c-0000-4000-8000-0000000000a1",
        "erasedAt": "2026-09-29T08:15:30.123456Z",
    },
    {
        "erasureRequestId": "a1b2c3d4-0000-4000-8000-000000000002",
        "authUserId": "0f0e0d0c-0000-4000-8000-0000000000a2",
        "erasedAt": "2026-09-29T08:16:00Z",
    },
    {
        "erasureRequestId": "a1b2c3d4-0000-4000-8000-000000000003",
        "authUserId": "0f0e0d0c-0000-4000-8000-0000000000a3",
        "erasedAt": "2026-09-29T08:17:45.120Z",
    },
)

# Canonical JSON parity: Java must serialise each value to exactly these bytes.
CANONICAL_JSON_CASES = (
    ("key order", {"b": 1, "a": {"d": [3, 2, 1], "c": None}, "A": True}),
    ("escapes", {"s": "quote\" backslash\\ slash/ nl\n tab\t cr\r bs\b ff\f nul\u0000 us\u001f del\u007f"}),
    ("non-ascii", {"tr": "ş ğ ü İ ı", "emoji": "\U0001F600", "bmp-end": "￿"}),
    ("astral key order", {"￿": 1, "\U0001F600": 2, "z": 3}),
    ("integers", {"zero": 0, "neg": -42, "big": 123456789012345678901234567890, "long": 9007199254740993}),
    ("empty containers", {"list": [], "map": {}, "nested": [{}, []]}),
    ("strings in lists", ["", "a", "ş", "\n"]),
)


def write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def pretty(payload) -> bytes:
    return (json.dumps(payload, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8")


def fresh_store(tmp: Path, name: str) -> IsolatedVersionedStore:
    return IsolatedVersionedStore(tmp / name)


def trusted(key_id, producer_id=None, not_before=KEY_NOT_BEFORE, not_after=None, retired=False):
    """A trust entry for one fixture key: secret from KEYS, metadata per case."""
    return TrustedKey(key_id, producer_id or KEYS[key_id]["producerId"], KEYS[key_id]["key"],
                      not_before, not_after, retired)


STANDARD_TRUST = EvidenceTrust(DATABASE_IDENTITY, [trusted(KEY_ID)])
ROTATION_TRUST = EvidenceTrust(DATABASE_IDENTITY, [
    trusted(KEY_ID, not_after=ROTATION_AT),
    trusted(ROTATED_KEY_ID, not_before=ROTATION_AT),
])


def coordinator(store, trust=STANDARD_TRUST, key_id=KEY_ID, signed_at=SIGNED_AT,
                **kwargs) -> IsolatedErasureCoordinator:
    return IsolatedErasureCoordinator(store, trust, key_id, clock=lambda: signed_at, **kwargs)


def valid_store(tmp: Path, name: str) -> IsolatedVersionedStore:
    store = fresh_store(tmp, name)
    coord = coordinator(store)
    for item in INPUTS:
        coord.request_deletion(item["authUserId"], item["erasureRequestId"], item["erasedAt"])
    return store


def rotated_store(tmp: Path, name: str) -> IsolatedVersionedStore:
    """Records 1 and 2 signed before the rotation, record 3 (and the frontier) after it."""
    store = fresh_store(tmp, name)
    before = coordinator(store, trust=ROTATION_TRUST)
    for item in INPUTS[:2]:
        before.request_deletion(item["authUserId"], item["erasureRequestId"], item["erasedAt"])
    after = coordinator(store, trust=ROTATION_TRUST, key_id=ROTATED_KEY_ID, signed_at=ROTATED_SIGNED_AT)
    after.request_deletion(INPUTS[2]["authUserId"], INPUTS[2]["erasureRequestId"], INPUTS[2]["erasedAt"])
    return store


def record_key(index: int) -> str:
    return erasure_record_id(INPUTS[index]["erasureRequestId"])


def load(store, key):
    return json.loads(store.get(key).decode("utf-8"))


def resign(body, fields, key_id=KEY_ID):
    body = dict(body)
    body.pop("signature", None)
    body["signature"] = sign(signed_subset(body, fields), KEYS[key_id]["key"])
    return body


def trust_json(trust):
    """A case's trust as metadata; the secrets come from producer.json by keyId."""
    return {
        "databaseIdentity": trust.database_identity,
        "keys": [
            {"keyId": key.key_id, "producerId": key.producer_id, "notBefore": key.not_before,
             "notAfter": key.not_after, "retired": key.retired}
            for key in trust.keys.values()
        ],
    }


def evaluate(store, trust, verified_at, required_through):
    """Reference verdict for one check; errors are reported by message."""
    try:
        result = recover_latest_trusted(
            store, trust, required_through_sequence=required_through, at=verified_at,
        )
    except ContractError as error:
        return {"requiredThrough": required_through, "error": str(error)}
    return {
        "requiredThrough": required_through,
        "verdict": result["verdict"],
        "expectedThrough": result["expectedThrough"],
        "latestTrustedSequence": result["latestTrustedSequence"],
        "latestTrustedCheckpoint": result["latestTrustedCheckpoint"],
        "listedMaximumSequence": result["listedMaximumSequence"],
        "gaps": result["gaps"],
        "abandonedReservations": result["abandonedReservations"],
        "pendingRequestIds": sorted(item["erasureRequestId"] for item in result["pending"]),
    }


def build_cases(tmp: Path):
    """Return (name, description, store, trust, verified_at, required_through_values)."""
    cases = []

    def add(name, description, store, required=(None,), trust=STANDARD_TRUST, verified_at=VERIFIED_AT):
        cases.append((name, description, store, trust, verified_at, list(required)))

    add("valid", "Three records, their sequence markers and the frontier, as the Python "
        "producer writes them. Java must write byte-identical objects.",
        valid_store(tmp, "valid"), required=(None, 3))

    store = valid_store(tmp, "missing-frontier")
    (store.root / FRONTIER_KEY).unlink()
    add("missing-frontier", "No independently durable expected boundary: UNKNOWN.", store,
        required=(None, 1))

    store = valid_store(tmp, "gap")
    (store.root / record_key(1)).unlink()
    add("gap", "Record 2 is missing below the frontier: BLOCKED with gap [2].", store,
        required=(None, 3))

    store = fresh_store(tmp, "abandoned-reservation")
    coord = coordinator(IsolatedVersionedStore(store.root, fail_on_prefix="records/"))
    try:
        coord.request_deletion(INPUTS[0]["authUserId"], INPUTS[0]["erasureRequestId"],
                               INPUTS[0]["erasedAt"])
    except PersistFailed:
        pass
    add("abandoned-reservation", "Sequence 1 was reserved but its record never published; "
        "the frontier is still 0: BLOCKED.", store)

    store = valid_store(tmp, "older-frontier")
    old = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": KIND_FRONTIER,
        "expectedThrough": 2,
        "highestReserved": 2,
        "databaseIdentity": DATABASE_IDENTITY,
        "producerId": PRODUCER_ID,
        "keyId": KEY_ID,
        "frontierDigest": frontier_digest(2, 2),
    }
    write(store.root / FRONTIER_KEY, canonical_bytes(resign(old, SIGNED_FRONTIER)))
    add("older-frontier", "A valid but older signed frontier (expectedThrough 2) beside "
        "record 3: record 3 is not trusted, and requiring sequence 3 is refused.", store,
        required=(None, 2, 3))

    store = valid_store(tmp, "tampered-signed-field")
    body = load(store, record_key(1))
    body["authUserId"] = INPUTS[0]["authUserId"]
    write(store.root / record_key(1), canonical_bytes(body))
    add("tampered-signed-field", "Record 2's authUserId was changed without re-signing.", store)

    store = valid_store(tmp, "tampered-erased-at")
    body = load(store, record_key(1))
    body["erasedAt"] = "2026-09-29T08:16:01Z"
    write(store.root / record_key(1), canonical_bytes(body))
    add("tampered-erased-at", "Record 2's erasedAt (bound by bodyDigest, not signed directly) "
        "was changed.", store)

    store = valid_store(tmp, "reordered-sequences")
    first, second = load(store, record_key(0)), load(store, record_key(1))
    first["sequence"], second["sequence"] = second["sequence"], first["sequence"]
    write(store.root / record_key(0), canonical_bytes(first))
    write(store.root / record_key(1), canonical_bytes(second))
    add("reordered-sequences", "Records 1 and 2 swapped their sequence numbers without "
        "re-signing.", store)

    store = valid_store(tmp, "record-copied-to-other-key")
    write(store.root / record_key(1), store.get(record_key(0)))
    add("record-copied-to-other-key", "Record 1's bytes were copied over record 2's key. Both "
        "verify (the key is not signed), so sequence 2 is a gap: BLOCKED.", store)

    add("wrong-database-identity", "The consumer's trust pins another database identity.",
        valid_store(tmp, "wrong-database-identity"),
        trust=EvidenceTrust(OTHER_DATABASE_IDENTITY, [trusted(KEY_ID)]))

    add("unknown-key", "The objects' keyId is not in the consumer's trust.",
        valid_store(tmp, "unknown-key"),
        trust=EvidenceTrust(DATABASE_IDENTITY, [trusted(OTHER_KEY_ID)]))

    store = valid_store(tmp, "wrong-producer-key")
    body = resign(load(store, record_key(1)), SIGNED_PENDING, key_id=OTHER_KEY_ID)
    write(store.root / record_key(1), canonical_bytes(body))
    add("wrong-producer-key", "Record 2 is signed with another producer's key under this "
        "producer's keyId.", store)

    store = valid_store(tmp, "wrong-kind")
    body = load(store, record_key(1))
    body["kind"] = "erasure-checkpoint"
    write(store.root / record_key(1), canonical_bytes(body))
    add("wrong-kind", "Record 2 claims another kind.", store)

    store = valid_store(tmp, "frontier-tampered")
    body = load(store, FRONTIER_KEY)
    body["expectedThrough"] = 2
    write(store.root / FRONTIER_KEY, canonical_bytes(body))
    add("frontier-tampered", "The frontier's expectedThrough was lowered without re-signing.",
        store)

    store = valid_store(tmp, "frontier-digest")
    body = load(store, FRONTIER_KEY)
    body["frontierDigest"] = frontier_digest(9, 9)
    write(store.root / FRONTIER_KEY, canonical_bytes(resign(body, SIGNED_FRONTIER)))
    add("frontier-digest", "A correctly signed frontier whose digest does not match its "
        "boundaries.", store)

    store = fresh_store(tmp, "checkpoint")
    coord = coordinator(store)
    for item in INPUTS[:2]:
        coord.request_deletion(item["authUserId"], item["erasureRequestId"], item["erasedAt"])
    sequence = SequenceAllocator(store).allocate("c0ffee00-0000-4000-8000-000000000001")
    entries = [
        {"authUserId": item["authUserId"], "erasureRequestId": item["erasureRequestId"],
         "erasedAt": item["erasedAt"], "note": "şehir"}
        for item in INPUTS[:2]
    ]
    coord.publish_checkpoint(sequence, entries)
    coord.advance_frontier(expected_through=sequence, highest_reserved=sequence)
    add("checkpoint", "Two records and a checkpoint at sequence 3 (non-ASCII ledger text).",
        store, required=(None, 3))

    store = fresh_store(tmp, "checkpoint-tampered")
    shutil.copytree(tmp / "checkpoint", store.root, dirs_exist_ok=True)
    body = load(store, checkpoint_key(3))
    body["entries"][0]["note"] = "sehir"
    write(store.root / checkpoint_key(3), canonical_bytes(body))
    add("checkpoint-tampered", "A checkpoint entry changed: the ledger digest no longer "
        "matches.", store)

    store = fresh_store(tmp, "checkpoint-ledger")
    coord = coordinator(store)
    for item in INPUTS[:2]:
        coord.request_deletion(item["authUserId"], item["erasureRequestId"], item["erasedAt"])
    sequence = SequenceAllocator(store).allocate(CHECKPOINT_RESERVATION_ID)
    entries = sorted(
        ({"authUserId": item["authUserId"], "erasedAt": item["erasedAt"]} for item in INPUTS),
        key=lambda entry: entry["authUserId"],
    )
    coord.publish_checkpoint(sequence, entries)
    coord.advance_frontier(expected_through=sequence, highest_reserved=sequence)
    add("checkpoint-ledger", "Two records and a checkpoint at sequence 3 as the auth-service "
        "producer writes it: one {authUserId, erasedAt} entry per tombstone ordered by "
        "authUserId (the third tombstone has no record), reserved by a marker with the "
        "checkpoint reservation id. Java must write byte-identical checkpoint and marker.",
        store, required=(None, 3))

    add("rotated-key", "Records 1 and 2 were signed with the old key before the rotation, "
        "record 3 and the frontier with the new key after it. Both keys are trusted: ACCEPT.",
        rotated_store(tmp, "rotated-key"), required=(None, 3), trust=ROTATION_TRUST)

    add("retired-key", "As rotated-key, but the old key is retired: everything it signed is "
        "refused.", rotated_store(tmp, "retired-key"),
        trust=EvidenceTrust(DATABASE_IDENTITY, [
            trusted(KEY_ID, not_after=ROTATION_AT, retired=True),
            trusted(ROTATED_KEY_ID, not_before=ROTATION_AT),
        ]))

    add("key-not-yet-valid", "As rotated-key, verified before the new key's notBefore: its "
        "objects are refused.", rotated_store(tmp, "key-not-yet-valid"),
        trust=ROTATION_TRUST, verified_at=BEFORE_ROTATION)

    add("key-of-another-producer", "The trust binds the objects' keyId to another producer.",
        valid_store(tmp, "key-of-another-producer"),
        trust=EvidenceTrust(DATABASE_IDENTITY, [trusted(KEY_ID, producer_id=OTHER_PRODUCER_ID)]))

    store = valid_store(tmp, "format-v1-object")
    body = load(store, record_key(1))
    body["schemaVersion"] = 1
    del body["keyId"]
    write(store.root / record_key(1), canonical_bytes(body))
    add("format-v1-object", "Record 2 has the format v1 shape (no keyId): refused.", store)

    return cases


def trust_document_cases():
    """Trust documents and the reference loader outcome: the error message, or null."""
    def document(**key_fields):
        key = {"keyId": KEY_ID, "producerId": PRODUCER_ID, "notBefore": KEY_NOT_BEFORE,
               "keyHex": KEYS[KEY_ID]["key"].hex()}
        key.update(key_fields)
        return {"format": "parkio-erasure-evidence-trust", "version": 1,
                "databaseIdentity": DATABASE_IDENTITY, "keys": [key]}

    duplicated = document()
    duplicated["keys"] = duplicated["keys"] * 2
    documents = [
        ("valid", document(notAfter=ROTATION_AT)),
        ("valid-retired", document(retired=True)),
        ("wrong-format", dict(document(), format="other")),
        ("wrong-version", dict(document(), version=2)),
        ("blank-database-identity", dict(document(), databaseIdentity=" ")),
        ("no-keys", dict(document(), keys=[])),
        ("missing-key-id", document(keyId="")),
        ("missing-producer-id", document(producerId=None)),
        ("key-not-hex", document(keyHex="zz")),
        ("short-key", document(keyHex="ab" * 31)),
        ("bad-not-before", document(notBefore="2026-01-01")),
        ("not-after-before-not-before", document(notAfter="2025-01-01T00:00:00Z")),
        ("retired-not-boolean", document(retired="no")),
        ("duplicate-key-id", duplicated),
    ]
    results = []
    for name, value in documents:
        try:
            EvidenceTrust.from_document(value)
            error = None
        except ContractError as exc:
            error = str(exc)
        results.append({"name": name, "document": value, "error": error})
    return results


def generate(out: Path) -> None:
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    write(out / "producer.json", pretty({
        "note": "Synthetic fixture keys; not secrets and never used outside tests.",
        "databaseIdentity": DATABASE_IDENTITY,
        "signedAt": SIGNED_AT,
        "keys": [
            {"keyId": key_id, "producerId": entry["producerId"], "keyHex": entry["key"].hex()}
            for key_id, entry in sorted(KEYS.items())
        ],
    }))
    write(out / "inputs.json", pretty([
        dict(item, sequence=index + 1) for index, item in enumerate(INPUTS)
    ]))
    write(out / "canonical-json.json", pretty([
        {"name": name, "value": value, "canonical": canonical_bytes(value).decode("ascii")}
        for name, value in CANONICAL_JSON_CASES
    ]))
    write(out / "trust-documents.json", pretty(trust_document_cases()))
    names = []
    with tempfile.TemporaryDirectory() as raw_tmp:
        tmp = Path(raw_tmp)
        for name, description, store, trust, verified_at, required in build_cases(tmp):
            names.append(name)
            case_dir = out / "cases" / name
            for key in store.list_prefix(""):
                write(case_dir / "store" / key, store.get(key))
            checks = [evaluate(store, trust, verified_at, value) for value in required]
            write(case_dir / "expected.json", pretty({
                "description": description,
                "trust": trust_json(trust),
                "verifiedAt": verified_at,
                "checks": checks,
            }))
    write(out / "cases.json", pretty(names))


def compare(expected: Path, actual: Path) -> list:
    problems = []
    cmp = filecmp.dircmp(expected, actual)
    stack = [(cmp, Path("."))]
    while stack:
        node, rel = stack.pop()
        problems += [f"only in committed fixtures: {rel / n}" for n in node.left_only]
        problems += [f"missing from committed fixtures: {rel / n}" for n in node.right_only]
        for name in node.common_files:
            if (Path(node.left) / name).read_bytes() != (Path(node.right) / name).read_bytes():
                problems.append(f"differs: {rel / name}")
        for name, sub in node.subdirs.items():
            stack.append((sub, rel / name))
    return sorted(problems)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail if committed fixtures drift")
    parser.add_argument("--out", type=Path, default=FIXTURE_DIR)
    args = parser.parse_args()
    if not args.check:
        generate(args.out)
        print(f"wrote {args.out.relative_to(ROOT) if args.out.is_relative_to(ROOT) else args.out}")
        return 0
    with tempfile.TemporaryDirectory() as raw_tmp:
        fresh = Path(raw_tmp) / "v1"
        generate(fresh)
        problems = compare(args.out, fresh)
    if problems:
        print("durable erasure evidence fixtures drifted:", *problems, sep="\n  ")
        return 1
    print("durable erasure evidence fixtures: up to date")
    return 0


if __name__ == "__main__":
    sys.exit(main())
