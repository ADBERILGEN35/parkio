#!/usr/bin/env python3
"""Evidence bundles and the trusted erasure set for isolated recovery (U02 stage 4).

An evidence bundle is a read-only, self-contained export of the off-host evidence
store: every pending record, sequence marker and checkpoint, plus every version of
the frontier, with the store's version ids. Recovery verifies the bundle, never a
claim about it, so the restored environment needs no store credentials.

The trusted erasure set is the latest trusted checkpoint's entries plus the
trusted pending records with a higher sequence (docs/operations/
recovery-evidence-contract.md, Checkpoints). auth-service computes the same set
from the same bundle (com.parkio.auth.application.durable.TrustedErasureSet); the
shared fixtures in services/auth-service/src/test/resources/
durable-erasure-evidence/v2/bundles pin both.

Coverage is reported only as the verified sequence: "erasure coverage verified
through sequence N (frontier version V)". Format v2 signs no time, so this module
never claims time-based coverage or that no later erasure exists.
"""
from __future__ import annotations

import base64
import json
import re

from recovery_evidence_contract import ContractError, canonical_bytes, sha256_hex
from recovery_persist_protocol import (
    FRONTIER_KEY,
    checkpoint_key,
    recover_latest_trusted,
    utc_now,
    verify_checkpoint,
)

BUNDLE_FORMAT = "parkio-erasure-evidence-bundle"
BUNDLE_VERSION = 1
TRUSTED_SET_FORMAT = "parkio-trusted-erasure-set"
TRUSTED_SET_VERSION = 1
OBJECT_PREFIXES = ("records/", "sequences/", "checkpoints/")
# What auth-service writes back identically: lowercase UUID text, and
# java.time.Instant#toString at microsecond precision (no fraction for whole
# seconds, otherwise 3 or 6 digits).
_UUID_TEXT = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
_INSTANT_TEXT = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3}|\.\d{6})?Z$")


def _check_entry_text(auth_user_id, erased_at):
    fraction = erased_at.partition(".")[2][:-1] if isinstance(erased_at, str) and "." in erased_at else ""
    if (not isinstance(auth_user_id, str) or not _UUID_TEXT.match(auth_user_id)
            or not isinstance(erased_at, str) or not _INSTANT_TEXT.match(erased_at)
            or fraction.endswith("000")):
        raise ContractError("erasure set entry is not in the evidence format")


def coverage_statement(verified_through, frontier_version):
    """The only coverage wording recovery reports."""
    return (f"erasure coverage verified through sequence {int(verified_through)} "
            f"(frontier version {frontier_version})")


def _b64(data):
    return base64.b64encode(data).decode("ascii")


def _unb64(text, label):
    if not isinstance(text, str):
        raise ContractError(f"bundle {label} is not base64 text")
    try:
        return base64.b64decode(text.encode("ascii"), validate=True)
    except (ValueError, UnicodeError) as exc:
        raise ContractError(f"bundle {label} is not base64 text") from exc


def _check_key(key):
    if not isinstance(key, str) or not key.startswith(OBJECT_PREFIXES) or ".." in key.split("/"):
        raise ContractError(f"bundle object key is not an evidence key: {key!r}")


def bundle_content_digest(bundle):
    """SHA-256 of the canonical bundle without its own digest field."""
    content = {key: value for key, value in bundle.items() if key != "bundleDigest"}
    return sha256_hex(canonical_bytes(content))


def encode_bundle(objects, frontier_versions, source):
    """A bundle from ``objects`` ({key: bytes}) and ``frontier_versions`` ([(id, bytes)])."""
    encoded_objects = {}
    for key in sorted(objects):
        _check_key(key)
        encoded_objects[key] = _b64(objects[key])
    versions = [{"versionId": str(version_id), "data": _b64(raw)} for version_id, raw in frontier_versions]
    bundle = {
        "format": BUNDLE_FORMAT,
        "version": BUNDLE_VERSION,
        "source": source,
        "objects": encoded_objects,
        "frontierVersions": versions,
    }
    bundle["bundleDigest"] = bundle_content_digest(bundle)
    return bundle


def bundle_from_store(store, source="isolated-versioned-store"):
    """Export a model store (one frontier version, id = SHA-256 of its bytes)."""
    objects = {}
    for prefix in OBJECT_PREFIXES:
        for key in store.list_prefix(prefix):
            objects[key] = store.get(key)
    frontier = store.get_optional(FRONTIER_KEY)
    versions = [] if frontier is None else [("sha256:" + sha256_hex(frontier), frontier)]
    return encode_bundle(objects, versions, source)


class BundleStore:
    """Read-only store over a bundle; exposes every frontier version."""

    durability_class = "evidence-bundle"

    def __init__(self, bundle):
        if not isinstance(bundle, dict):
            raise ContractError("evidence bundle is not a JSON object")
        if bundle.get("format") != BUNDLE_FORMAT or bundle.get("version") != BUNDLE_VERSION:
            raise ContractError("unsupported evidence bundle format")
        if bundle.get("bundleDigest") != bundle_content_digest(bundle):
            raise ContractError("evidence bundle digest mismatch")
        objects = bundle.get("objects")
        versions = bundle.get("frontierVersions")
        if not isinstance(objects, dict) or not isinstance(versions, list):
            raise ContractError("evidence bundle is incomplete")
        self._objects = {}
        for key, value in objects.items():
            _check_key(key)
            self._objects[key] = _unb64(value, "object")
        self._versions = []
        seen = set()
        for item in versions:
            if not isinstance(item, dict) or not isinstance(item.get("versionId"), str) or not item["versionId"]:
                raise ContractError("evidence bundle frontier version has no id")
            if item["versionId"] in seen:
                raise ContractError("evidence bundle repeats a frontier version id")
            seen.add(item["versionId"])
            self._versions.append((item["versionId"], _unb64(item.get("data"), "frontier version")))

    def get(self, key):
        if key == FRONTIER_KEY:
            raise ContractError("the frontier is versioned; read all of its versions")
        if key not in self._objects:
            raise ContractError(f"missing publication {key}")
        return self._objects[key]

    def get_optional(self, key):
        if key == FRONTIER_KEY:
            raise ContractError("the frontier is versioned; read all of its versions")
        return self._objects.get(key)

    def exists(self, key):
        return key in self._objects

    def list_prefix(self, prefix):
        return sorted(key for key in self._objects if key.startswith(prefix))

    def get_versions(self, key):
        if key != FRONTIER_KEY:
            raise ContractError(f"only the frontier is versioned: {key}")
        return list(self._versions)


def trusted_erasure_set(store, trust, at=None):
    """The erasure set recovery replays, or ContractError when it is not trusted.

    Only an ACCEPT_ISOLATED recovery (highest verified frontier version, no gap) is
    used. Entries come from the latest trusted checkpoint and the trusted pending
    records above it; a pending record below that checkpoint must already be in its
    ledger. One user with two different erasure times fails closed.
    """
    at = at or utc_now()
    recovered = recover_latest_trusted(store, trust, at=at)
    if recovered["verdict"] != "ACCEPT_ISOLATED":
        raise ContractError(f"{recovered['verdict']}: {recovered['reason']}; recovery BLOCKED")
    expected_through = int(recovered["expectedThrough"])
    checkpoint_sequence = recovered["latestTrustedCheckpoint"]
    ledger = {}
    if checkpoint_sequence is not None:
        body = verify_checkpoint(store, checkpoint_key(checkpoint_sequence), trust, at=at)
        for entry in body["entries"]:
            ledger[entry["authUserId"]] = entry["erasedAt"]
    entries = dict(ledger)
    for record in recovered["pending"]:
        user, erased_at = record["authUserId"], record["erasedAt"]
        if checkpoint_sequence is not None and record["sequence"] < checkpoint_sequence:
            if ledger.get(user) != erased_at:
                raise ContractError("a pending record below the latest checkpoint is not in its ledger")
            continue
        if user in entries and entries[user] != erased_at:
            raise ContractError("conflicting erasedAt for one user in the trusted erasure set")
        entries[user] = erased_at
    for user, erased_at in entries.items():
        _check_entry_text(user, erased_at)
    ordered = [{"authUserId": user, "erasedAt": entries[user]} for user in sorted(entries)]
    digest = sha256_hex(canonical_bytes({"kind": "erasure-ledger", "entries": ordered}))
    return {
        "verifiedThroughSequence": expected_through,
        "frontierVersion": recovered["frontierVersion"],
        "latestTrustedCheckpoint": checkpoint_sequence,
        "ignoredFrontierVersions": recovered["ignoredFrontierVersions"],
        "statement": coverage_statement(expected_through, recovered["frontierVersion"]),
        "erasureSetDigest": digest,
        "entries": ordered,
    }


def trusted_set_document(bundle, trust, recovery_attempt_id, restored_dataset_id, target_identity, at=None):
    """The file the isolated restore hands to the auth recovery-replay command.

    It embeds the bundle; the command re-verifies the bundle against its own trust
    document and must derive the same coverage and erasure set.
    """
    for label, value in (("recoveryAttemptId", recovery_attempt_id),
                         ("restoredDatasetId", restored_dataset_id),
                         ("targetIdentity", target_identity)):
        if not isinstance(value, str) or not value.strip():
            raise ContractError(f"{label} is required")
    if target_identity == trust.database_identity:
        raise ContractError("target identity is the production identity pinned in the trust document")
    verified = trusted_erasure_set(BundleStore(bundle), trust, at=at)
    return {
        "format": TRUSTED_SET_FORMAT,
        "version": TRUSTED_SET_VERSION,
        "recoveryAttemptId": recovery_attempt_id,
        "restoredDatasetId": restored_dataset_id,
        "targetIdentity": target_identity,
        "evidenceDatabaseIdentity": trust.database_identity,
        "coverage": {
            "verifiedThroughSequence": verified["verifiedThroughSequence"],
            "frontierVersion": verified["frontierVersion"],
            "latestTrustedCheckpoint": verified["latestTrustedCheckpoint"],
            "statement": verified["statement"],
        },
        "erasureSet": {
            "erasureSetDigest": verified["erasureSetDigest"],
            "entries": verified["entries"],
        },
        "bundle": bundle,
    }


def load_json(path):
    with open(path, "rb") as handle:
        try:
            return json.loads(handle.read().decode("utf-8"))
        except (ValueError, UnicodeError) as exc:
            raise ContractError(f"{path} is not JSON") from exc
