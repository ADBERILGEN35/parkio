#!/usr/bin/env python3
"""Generate the cross-language durable erasure evidence fixtures (format v1).

The Python persist protocol (scripts/lib/recovery_persist_protocol.py) is the
reference producer. This script runs it against a throwaway directory store and
writes deterministic store snapshots plus expected verdicts. The Java producer and
verifier in auth-service (com.parkio.auth.application.durable) are tested against
the same files: Java must write byte-identical records, markers, frontier and
checkpoint, and both verifiers must reach the same verdict or error for every case.

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
    SIGNED_FRONTIER,
    SIGNED_PENDING,
    IsolatedErasureCoordinator,
    IsolatedVersionedStore,
    SequenceAllocator,
    checkpoint_key,
    erasure_record_id,
    frontier_digest,
    recover_latest_trusted,
    signed_subset,
)

FIXTURE_DIR = ROOT / "services/auth-service/src/test/resources/durable-erasure-evidence/v1"
DATABASE_IDENTITY = "auth-db:interop-fixture"
PRODUCER_ID = "interop-fixture-producer"
PRODUCER_KEY = b"parkio-durable-interop-fixture-not-a-secret"
OTHER_PRODUCER_ID = "interop-other-producer"
OTHER_PRODUCER_KEY = b"parkio-durable-interop-other-not-a-secret"

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


def coordinator(store, **kwargs) -> IsolatedErasureCoordinator:
    return IsolatedErasureCoordinator(store, DATABASE_IDENTITY, PRODUCER_ID, PRODUCER_KEY, **kwargs)


def valid_store(tmp: Path, name: str) -> IsolatedVersionedStore:
    store = fresh_store(tmp, name)
    coord = coordinator(store)
    for item in INPUTS:
        coord.request_deletion(item["authUserId"], item["erasureRequestId"], item["erasedAt"])
    return store


def record_key(index: int) -> str:
    return erasure_record_id(INPUTS[index]["erasureRequestId"])


def load(store, key):
    return json.loads(store.get(key).decode("utf-8"))


def resign(body, fields, key=PRODUCER_KEY):
    body = dict(body)
    body.pop("signature", None)
    body["signature"] = sign(signed_subset(body, fields), key)
    return body


def evaluate(store, expected_db, trusted, required_through):
    """Reference verdict for one check; errors are reported by message."""
    keys = {PRODUCER_ID: PRODUCER_KEY, OTHER_PRODUCER_ID: OTHER_PRODUCER_KEY}
    trusted_keys = {name: keys[name] for name in trusted}
    try:
        result = recover_latest_trusted(
            store, expected_db, trusted_keys, required_through_sequence=required_through,
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
    """Return (name, description, store, expected_db, trusted, required_through_values)."""
    cases = []

    def add(name, description, store, required=(None,), expected_db=DATABASE_IDENTITY,
            trusted=(PRODUCER_ID,)):
        cases.append((name, description, store, expected_db, list(trusted), list(required)))

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
        "schemaVersion": 1,
        "kind": KIND_FRONTIER,
        "expectedThrough": 2,
        "highestReserved": 2,
        "databaseIdentity": DATABASE_IDENTITY,
        "producerId": PRODUCER_ID,
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

    add("wrong-database-identity", "The consumer expects another database identity.",
        valid_store(tmp, "wrong-database-identity"), expected_db="auth-db:some-other-database")

    add("unknown-producer", "The producer is not in the consumer's trusted keys.",
        valid_store(tmp, "unknown-producer"), trusted=(OTHER_PRODUCER_ID,))

    store = valid_store(tmp, "wrong-producer-key")
    body = resign(load(store, record_key(1)), SIGNED_PENDING, key=OTHER_PRODUCER_KEY)
    write(store.root / record_key(1), canonical_bytes(body))
    add("wrong-producer-key", "Record 2 is signed with another producer's key under this "
        "producer's id.", store)

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

    return cases


def generate(out: Path) -> None:
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    write(out / "producer.json", pretty({
        "note": "Synthetic fixture keys; not secrets and never used outside tests.",
        "databaseIdentity": DATABASE_IDENTITY,
        "producerId": PRODUCER_ID,
        "producerKeyHex": PRODUCER_KEY.hex(),
        "otherProducerId": OTHER_PRODUCER_ID,
        "otherProducerKeyHex": OTHER_PRODUCER_KEY.hex(),
    }))
    write(out / "inputs.json", pretty([
        dict(item, sequence=index + 1) for index, item in enumerate(INPUTS)
    ]))
    write(out / "canonical-json.json", pretty([
        {"name": name, "value": value, "canonical": canonical_bytes(value).decode("ascii")}
        for name, value in CANONICAL_JSON_CASES
    ]))
    names = []
    with tempfile.TemporaryDirectory() as raw_tmp:
        tmp = Path(raw_tmp)
        for name, description, store, expected_db, trusted, required in build_cases(tmp):
            names.append(name)
            case_dir = out / "cases" / name
            for key in store.list_prefix(""):
                write(case_dir / "store" / key, store.get(key))
            checks = [evaluate(store, expected_db, trusted, value) for value in required]
            write(case_dir / "expected.json", pretty({
                "description": description,
                "expectedDatabaseIdentity": expected_db,
                "trustedProducers": trusted,
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
