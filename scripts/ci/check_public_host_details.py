#!/usr/bin/env python3
"""Public docs carry no personal addresses or SSH logins (G-05, #487).

The repository is public. A tracked text file under docs/ or agent-tools/ fails the check when a line has
  1. a login at a raw IPv4 address (`user@203.0.113.7`);
  2. a login at a Parkio host name (`user@api.parkio.dev`, `user@parkio-civo-prod`); mail addresses use the
     apex `@parkio.dev`;
  3. `user@host` in an ssh, scp, sftp or rsync line; write placeholders as `<user>@<host>`;
     an SSH user named outright (`over SSH as \`deploy\``, `SSH_USER=deploy`);
  4. an IPv4 address next to a personal or residential machine;
  5. an IPv4 address next to SSH access or the production host name.
Local addresses (loopback, RFC 1918, link-local, 0.0.0.0/8, 100.64.0.0/10) identify no one and pass,
so `127.0.0.1:9093 via SSH tunnel` is fine. Write roles instead of the rest: "developer workstation",
"the production host (owner access)". The values stay in the git history; this guards the tree only.

Text in UTF-16 (with or without a BOM) is decoded too, and any other 8-bit text as Latin-1, which keeps
every ASCII pattern intact; only binary files are skipped, and the summary counts them.

Usage: check_public_host_details.py [file ...]   (default: tracked files under docs/ and agent-tools/)
Exit 1 lists file:line: rule. The matched text is not printed, so the CI log does not repeat it.
"""
from __future__ import annotations

import codecs
import ipaddress
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCOPES = ("docs", "agent-tools")
# A sentence may end right after the address ("... is 203.0.113.7."), a version string may not.
IPV4 = re.compile(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?!\.?\d)")
LOGIN_AT_IPV4 = re.compile(r"[A-Za-z0-9_.-]@((?:\d{1,3}\.){3}\d{1,3})(?!\.?\d)")
LOGIN_AT_PARKIO_HOST = re.compile(r"[A-Za-z0-9_.-]@(?:(?:[A-Za-z0-9-]+\.)+parkio\.dev\b|parkio-civo-prod\b)", re.I)
SSH_LINE = re.compile(r"\b(?:ssh|scp|sftp|rsync)\b", re.I)
SSH_USER_NAMED = re.compile(r"\bSSH_USER\s*=\s*[A-Za-z_]|\bSSH\b.{0,60}\bas\s+`[A-Za-z_][A-Za-z0-9_.-]*`", re.I)
LOGIN_AT_HOST = re.compile(r"[A-Za-z0-9_.-]@[A-Za-z0-9-]")
PERSONAL = re.compile(r"\b(?:residential|home network|home IP|developer machine|laptop|personal)\b", re.I)
ACCESS = re.compile(r"\bssh\b|parkio-civo-prod", re.I)
LOCAL = tuple(ipaddress.ip_network(net) for net in
              ("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
               "172.16.0.0/12", "192.168.0.0/16"))


def reportable(address: str) -> bool:
    try:
        ip = ipaddress.IPv4Address(address)
    except ValueError:
        return False
    return not any(ip in net for net in LOCAL)


def has_reportable_ipv4(line: str) -> bool:
    return any(reportable(match.group(0)) for match in IPV4.finditer(line))


RULES = (
    ("login at a raw IP address", lambda line: any(reportable(m.group(1)) for m in LOGIN_AT_IPV4.finditer(line))),
    ("login at a Parkio host name", lambda line: bool(LOGIN_AT_PARKIO_HOST.search(line))),
    ("user@host in an ssh/scp/sftp/rsync line",
     lambda line: bool(SSH_LINE.search(line) and LOGIN_AT_HOST.search(line))),
    ("SSH user named", lambda line: bool(SSH_USER_NAMED.search(line))),
    ("IP address of a personal or residential machine",
     lambda line: bool(PERSONAL.search(line)) and has_reportable_ipv4(line)),
    ("IP address next to SSH access or the production host",
     lambda line: bool(ACCESS.search(line)) and has_reportable_ipv4(line)),
)


def decode(data: bytes) -> str | None:
    """The text of a file, or None for a binary one."""
    if data.startswith((codecs.BOM_UTF16_LE, codecs.BOM_UTF16_BE)):
        return data.decode("utf-16", errors="replace")
    if b"\0" in data:
        # UTF-16 without a BOM: in mostly-ASCII text every other byte is NUL.
        if len(data) % 2 == 0 and data[1::2].count(0) * 2 >= len(data) // 2:
            return data.decode("utf-16-le", errors="replace")
        if len(data) % 2 == 0 and data[0::2].count(0) * 2 >= len(data) // 2:
            return data.decode("utf-16-be", errors="replace")
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("latin-1")


def problems_in(name: str, text: str) -> list[str]:
    found = []
    for number, line in enumerate(text.splitlines(), 1):
        for reason, rule in RULES:
            if rule(line):
                found.append(f"{name}:{number}: {reason}")
    return found


def tracked_files() -> list[Path]:
    out = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z", "--", *SCOPES],
                         check=True, capture_output=True).stdout
    return [ROOT / name for name in out.decode("utf-8").split("\0") if name]


def main(argv: list[str]) -> int:
    paths = [Path(arg) for arg in argv] or tracked_files()
    problems = []
    skipped = 0
    for path in paths:
        text = decode(path.read_bytes())
        if text is None:
            skipped += 1
            continue
        name = path.relative_to(ROOT).as_posix() if path.is_absolute() and ROOT in path.parents else str(path)
        problems.extend(problems_in(name, text))
    if problems:
        print("Public host details check FAILED (use a role, e.g. 'the production host (owner access)'):\n  "
              + "\n  ".join(problems))
        return 1
    print(f"Public host details check passed: {len(paths) - skipped} text file(s) scanned, {skipped} binary "
          "file(s) skipped; no personal address or SSH login.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
