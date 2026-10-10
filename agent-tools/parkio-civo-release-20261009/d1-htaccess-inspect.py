#!/usr/bin/env python3
"""D1 .htaccess inspection - what differs between the live parkio.dev .htaccess and the recorded and release versions?

Owner workstation, Windows Terminal -> Ubuntu (WSL); read-only:
  python3 -I /tmp/d1-htaccess-inspect.py ~/parkio-marketing-faf088fe5626/live-backup/public_html-pre-m2-20261010.zip

It reads only the root .htaccess inside the backup and compares it with the four versions this repository has ever held
(embedded below and checked against their git blob ids): 621dea53 (1edb16b2), 34b8af43 (ad384269), d3b6360d (e7b6a842, the
recorded live version) and 87aec6ec (fbfa218f, the release version in faf088fe). Output:
  A. facts: size, lines, CRLF lines, BOM, final newline, non-ASCII bytes, the git blob id (raw and LF-normalized) and which
     known version it equals, and whether it equals the recorded version once formatting (line endings, trailing spaces,
     blank lines) is ignored;
  B. the differing lines, recorded -> live and live -> release, each shown through a redactor: known directives with
     values that are not secrets (Options, DirectoryIndex, ErrorDocument, Expires*, block tags, Rewrite*, Redirect*, and
     Header for a fixed list of security and caching header names) are shown in full; every other directive (SetEnv*,
     php_*, Auth*, Require/Allow/Deny/Order, RequestHeader, Header with another name, anything unknown) shows only its
     name and a short hash of the line. E-mail addresses, IP addresses, query-string values and long tokens are masked on
     every line, comments included.
It writes nothing and changes nothing.
"""
import difflib
import hashlib
import os
import re
import sys
import zipfile

KNOWN = [
    ("621dea53cc1502424bc6fc46558bb28779259640", "1edb16b2", "2026-09-04", "baseline import of the pre-01D live package",
     "Options -Indexes\nDirectoryIndex index.html\nErrorDocument 404 /404.html\n\n<IfModule mod_headers.c>\n  Header always set X-Content-Type-Options \"nosniff\"\n  Header always set Referrer-Policy \"strict-origin-when-cross-origin\"\n  Header always set X-Frame-Options \"SAMEORIGIN\"\n  Header always set Permissions-Policy \"camera=(), microphone=(), geolocation=(self)\"\n  Header always set Content-Security-Policy \"default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'self'; frame-ancestors 'self'\"\n</IfModule>\n\n<IfModule mod_expires.c>\n  ExpiresActive On\n  ExpiresByType image/png \"access plus 30 days\"\n  ExpiresByType text/css \"access plus 7 days\"\n</IfModule>\n"),
    ("34b8af43b4b1dafce567e338e195377a3ccff173", "ad384269", "2026-09-21", "allow waitlist API under CSP",
     "Options -Indexes\nDirectoryIndex index.html\nErrorDocument 404 /404.html\n\n<IfModule mod_headers.c>\n  Header always set X-Content-Type-Options \"nosniff\"\n  Header always set Referrer-Policy \"strict-origin-when-cross-origin\"\n  Header always set X-Frame-Options \"SAMEORIGIN\"\n  Header always set Permissions-Policy \"camera=(), microphone=(), geolocation=(self)\"\n  # connect-src must allow the gateway API; default-src 'self' alone blocks fetch to api.parkio.dev\n  Header always set Content-Security-Policy \"default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self' 'unsafe-inline'; connect-src 'self' https://api.parkio.dev; object-src 'none'; base-uri 'self'; frame-ancestors 'self'\"\n</IfModule>\n\n<IfModule mod_expires.c>\n  ExpiresActive On\n  ExpiresByType image/png \"access plus 30 days\"\n  ExpiresByType text/css \"access plus 7 days\"\n</IfModule>\n"),
    ("d3b6360d725b6d6473dc047fd2afbc89b455eb46", "e7b6a842", "2026-09-21", "recorded live version (JS cache headers); also at db3f4a3d",
     "Options -Indexes\nDirectoryIndex index.html\nErrorDocument 404 /404.html\n\n<IfModule mod_headers.c>\n  Header always set X-Content-Type-Options \"nosniff\"\n  Header always set Referrer-Policy \"strict-origin-when-cross-origin\"\n  Header always set X-Frame-Options \"SAMEORIGIN\"\n  Header always set Permissions-Policy \"camera=(), microphone=(), geolocation=(self)\"\n  # connect-src must allow the gateway API; default-src 'self' alone blocks fetch to api.parkio.dev\n  Header always set Content-Security-Policy \"default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self' 'unsafe-inline'; connect-src 'self' https://api.parkio.dev; object-src 'none'; base-uri 'self'; frame-ancestors 'self'\"\n</IfModule>\n\n<IfModule mod_expires.c>\n  ExpiresActive On\n  ExpiresByType image/png \"access plus 30 days\"\n  ExpiresByType text/css \"access plus 7 days\"\n  ExpiresByType application/javascript \"access plus 5 minutes\"\n  ExpiresByType text/javascript \"access plus 5 minutes\"\n</IfModule>\n\n<IfModule mod_headers.c>\n  <FilesMatch \"\\.(js)$\">\n    Header set Cache-Control \"public, max-age=300\"\n  </FilesMatch>\n</IfModule>\n"),
    ("87aec6ec1699aef86274169b0f8573ff0496b721", "fbfa218f", "2026-10-02", "release version in faf088fe (no script unsafe-inline, HSTS)",
     "Options -Indexes\nDirectoryIndex index.html\nErrorDocument 404 /404.html\n\n<IfModule mod_headers.c>\n  Header always set X-Content-Type-Options \"nosniff\"\n  Header always set Referrer-Policy \"strict-origin-when-cross-origin\"\n  Header always set X-Frame-Options \"SAMEORIGIN\"\n  Header always set Permissions-Policy \"camera=(), microphone=(), geolocation=(self)\"\n  # connect-src must allow the gateway API; default-src 'self' alone blocks fetch to api.parkio.dev\n  # script-src 'self': pages run no inline script; the JSON-LD block is data and needs no allowance\n  Header always set Content-Security-Policy \"default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self' https://api.parkio.dev; object-src 'none'; base-uri 'self'; frame-ancestors 'self'\"\n  # parkio.dev only: includeSubDomains or preload would bind every *.parkio.dev host (owner decision)\n  Header always set Strict-Transport-Security \"max-age=31536000\"\n</IfModule>\n\n<IfModule mod_expires.c>\n  ExpiresActive On\n  ExpiresByType image/png \"access plus 30 days\"\n  ExpiresByType text/css \"access plus 7 days\"\n  ExpiresByType application/javascript \"access plus 5 minutes\"\n  ExpiresByType text/javascript \"access plus 5 minutes\"\n</IfModule>\n\n<IfModule mod_headers.c>\n  <FilesMatch \"\\.(js)$\">\n    Header set Cache-Control \"public, max-age=300\"\n  </FilesMatch>\n</IfModule>\n"),
]

RECORDED, RELEASE = "d3b6360d725b6d6473dc047fd2afbc89b455eb46", "87aec6ec1699aef86274169b0f8573ff0496b721"
FULL = {"options", "directoryindex", "errordocument", "expiresactive", "expiresbytype", "expiresdefault", "addtype",
        "addcharset", "adddefaultcharset", "addencoding", "addoutputfilterbytype", "fileetag", "rewriteengine",
        "rewritebase", "rewriterule", "rewritecond", "redirect", "redirectmatch", "redirectpermanent", "forcetype",
        "defaulttype", "<ifmodule", "</ifmodule>", "<filesmatch", "</filesmatch>", "<files", "</files>", "<if", "</if>",
        "<else>", "</else>", "<elseif", "</elseif>", "<ifdefine", "</ifdefine>", "<limit", "</limit>", "<limitexcept",
        "</limitexcept>", "serversignature"}
SAFE_HEADERS = {"x-content-type-options", "referrer-policy", "x-frame-options", "permissions-policy",
                "content-security-policy", "content-security-policy-report-only", "strict-transport-security",
                "cache-control", "expires", "x-xss-protection", "cross-origin-opener-policy",
                "cross-origin-embedder-policy", "cross-origin-resource-policy", "vary", "x-robots-tag"}
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")
IPV4 = re.compile(r"\b\d{1,3}(?:\\?\.\d{1,3}){3}(?:/\d{1,2})?\b")  # also regex-escaped dots
IPV6 = re.compile(r"\b(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?:/\d{1,3})?\b")
QUERY = re.compile(r"([?&][^=\s&\"']+=)[^&\s\"']+")
TOKEN = re.compile(r"[A-Za-z0-9+/_=-]{24,}")


def blob_id(data):
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def mask(text):
    text = EMAIL.sub("<email>", text)
    text = QUERY.sub(r"\1<value>", text)
    text = IPV4.sub("<ip>", text)
    text = IPV6.sub(lambda m: "<ip>" if m.group(0).count(":") >= 2 and re.search(r"[0-9a-fA-F]", m.group(0)) else m.group(0), text)
    return TOKEN.sub(lambda m: m.group(0) if m.group(0).lower() in SAFE_HEADERS else f"<token:{len(m.group(0))}>", text)


def show(line):
    stripped = line.strip()
    if not stripped:
        return "(blank)"
    if stripped.startswith("#"):
        return mask(line.rstrip())
    word = stripped.split(None, 1)[0].lower()
    tag = hashlib.sha256(stripped.encode("utf-8", "surrogateescape")).hexdigest()[:8]
    if word in FULL:
        return mask(line.rstrip())
    if word == "header":
        m = re.match(r"\s*Header\s+(?:always\s+)?(\w+)\s+([\w-]+)", line, re.I)
        if m and m.group(2).lower() in SAFE_HEADERS:
            return mask(line.rstrip())
        name = m.group(2) if m else "?"
        return f"Header {m.group(1) if m else '?'} {name} <value redacted, line {tag}>"
    return f"{stripped.split(None, 1)[0]} <value redacted, line {tag}>"


def read_live(path):
    if os.path.isfile(path) and os.path.basename(path) == ".htaccess":
        return open(path, "rb").read(), "file"
    with zipfile.ZipFile(path) as z:
        names = [n for n in z.namelist() if not n.endswith("/")]
        norm = {}
        for n in names:
            m = n
            while m.startswith("./"):
                m = m[2:]
            norm[m.lstrip("/")] = n
        roots = [n[: -len("waitlist/confirm/index.html")] for n in norm if n.endswith("waitlist/confirm/index.html")]
        if len(roots) != 1:
            print("STOP: cannot locate the site root in the backup")
            sys.exit(2)
        key = roots[0] + ".htaccess"
        if key not in norm:
            print("STOP: the backup has no root .htaccess")
            sys.exit(2)
        return z.read(norm[key]), f"zip root '{roots[0] or '.'}'"


def lines_of(data):
    return data.decode("utf-8", "surrogateescape").replace("\r\n", "\n").split("\n")


def canonical(data):
    if data.startswith(b"\xef\xbb\xbf"):
        data = data[3:]
    rows = [ln.rstrip() for ln in lines_of(data)]
    return [r for r in rows if r.strip()]


def main():
    if len(sys.argv) != 2:
        print("usage: python3 -I d1-htaccess-inspect.py <backup .zip or a .htaccess file>")
        sys.exit(2)
    for blob, commit, _, _, text in KNOWN:
        if blob_id(text.encode("ascii")) != blob:
            print(f"STOP: embedded version {blob[:8]} does not match its blob id")
            sys.exit(2)
    known = {b: (c, d, n, t.encode("ascii")) for b, c, d, n, t in KNOWN}
    live, where = read_live(sys.argv[1])
    print(f"D1 .htaccess inspection | {where}")
    print("A. Facts (no content)")
    lf = live.replace(b"\r\n", b"\n")
    lines = lines_of(live)
    crlf, bom, final_nl, nul = live.count(b"\r\n"), live.startswith(b"\xef\xbb\xbf"), live.endswith(b"\n"), live.count(b"\0")
    count = len(lines) - (1 if lines and lines[-1] == "" else 0)
    print(f"  size {len(live)} bytes; lines {count}; CRLF lines {crlf}; BOM {'yes' if bom else 'no'}; "
          f"final newline {'yes' if final_nl else 'no'}; non-ASCII bytes {sum(1 for b in live if b > 127)}; NUL bytes {nul}")
    raw_id, lf_id = blob_id(live), blob_id(lf)
    match = known.get(raw_id) or known.get(lf_id)
    print(f"  git blob id: raw {raw_id[:12]}, LF-normalized {lf_id[:12]}")
    print("  equals a known version: " + (f"{(raw_id if raw_id in known else lf_id)[:8]} = {match[0]} ({match[1]}): {match[2]}"
                                           + ("" if raw_id in known else " after CRLF -> LF") if match else "none"))
    for blob, label in ((RECORDED, "recorded"), (RELEASE, "release")):
        same = canonical(live) == canonical(known[blob][3])
        print(f"  equals the {label} version {blob[:8]} ignoring line endings, trailing spaces and blank lines: {'yes' if same else 'no'}")
    for blob, (commit, _, _, text) in known.items():
        if blob in (RECORDED, RELEASE):
            continue
        if canonical(live) == canonical(text):
            print(f"  equals {blob[:8]} ({commit}) ignoring formatting: yes")
    rec, rel = canonical(known[RECORDED][3]), canonical(known[RELEASE][3])
    cur = canonical(live)
    print("")
    print(f"B1. recorded {RECORDED[:8]} -> live (formatting ignored; '-' only in recorded, '+' only on live)")
    diff = [d for d in difflib.unified_diff(rec, cur, lineterm="", n=0) if not d.startswith(("---", "+++"))]
    print("  " + ("\n  ".join(d if d.startswith("@@") else d[0] + " " + show(d[1:]) for d in diff) if diff else "(no difference)"))
    print("")
    print(f"B2. live -> release {RELEASE[:8]} (what the upload changes: '-' live lines the upload removes, '+' it adds)")
    diff = [d for d in difflib.unified_diff(cur, rel, lineterm="", n=0) if not d.startswith(("---", "+++"))]
    print("  " + ("\n  ".join(d if d.startswith("@@") else d[0] + " " + show(d[1:]) for d in diff) if diff else "(no difference)"))
    removed = [show(d[1:]) for d in diff if d.startswith("-")]
    print("")
    print(f"SUMMARY: live lines the upload would remove: {len(removed)}; lines it would add: "
          f"{sum(1 for d in diff if d.startswith('+'))}")
    print("INSPECTION COMPLETE (read-only)")


if __name__ == "__main__":
    main()
