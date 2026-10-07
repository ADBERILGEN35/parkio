#!/usr/bin/env python3
"""Verify off-host erasure evidence before an isolated restore decrypts or applies anything.

  recovery-evidence.py verify --bundle BUNDLE --trust TRUST --attempt UUID --dataset ID \\
      --target-identity IDENTITY --out TRUSTED_SET

It writes the trusted-set file the auth recovery-replay command consumes
(scripts/lib/recovery_evidence_bundle.py, trusted_set_document) and prints the coverage
statement. Exit codes: 0 trusted, 3 BLOCKED (missing, corrupt, gap or otherwise untrusted
evidence; nothing may be decrypted or applied), 2 usage. The trust document holds HMAC
secrets and is never printed. Coverage is reported only as the verified sequence; no time-based
coverage or absence of later erasures is claimed.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from recovery_evidence_bundle import load_json, trusted_set_document  # noqa: E402
from recovery_evidence_contract import ContractError  # noqa: E402
from recovery_persist_protocol import EvidenceTrust  # noqa: E402


def verify(args) -> int:
    try:
        attempt = str(uuid.UUID(args.attempt))
    except ValueError:
        print("ERROR: --attempt must be a UUID", file=sys.stderr)
        return 2
    if attempt != args.attempt:
        print("ERROR: --attempt must be a lowercase UUID", file=sys.stderr)
        return 2
    out = Path(args.out)
    if not out.parent.is_dir():
        print("ERROR: --out directory does not exist", file=sys.stderr)
        return 2
    try:
        trust = EvidenceTrust.from_document(load_json(args.trust))
        document = trusted_set_document(load_json(args.bundle), trust, attempt, args.dataset,
                                        args.target_identity)
    except (ContractError, OSError) as error:
        print(f"BLOCKED: erasure evidence is not trusted: {error}", file=sys.stderr)
        return 3
    temporary = out.with_name(f".{out.name}.tmp")
    temporary.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.chmod(temporary, 0o600)
    temporary.replace(out)
    print(document["coverage"]["statement"])
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    check = sub.add_parser("verify", help="verify a bundle and write the trusted-set file")
    for name in ("--bundle", "--trust", "--attempt", "--dataset", "--target-identity", "--out"):
        check.add_argument(name, required=True)
    args = parser.parse_args(argv)
    return verify(args)


if __name__ == "__main__":
    sys.exit(main())
