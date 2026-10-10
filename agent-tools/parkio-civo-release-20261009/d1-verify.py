#!/usr/bin/env python3
"""D1 verification - is the reviewed marketing bundle live on parkio.dev? (public GETs only; approved decision M2)

Run as civo on the Civo host after the Hostinger upload and CDN purge:
  python3 -I /tmp/d1-verify.py

Checks, all read-only:
  - every file of the bundle (PR #331 merge) is served with the expected SHA-256 (raw, or after CRLF -> LF);
    images only need to be present as image/* (the CDN re-encodes them; their origin bytes are checked before upload);
    waitlist.js and the landing page are fetched both plain and with ?v=w01n1, as browsers request them;
  - the landing page has parkio-waitlist-mode=api and loads styles.css, i18n.js and waitlist.js with ?v=w01n1;
    waitlist.js carries waitlist-consent-v1;
  - response headers of / and /waitlist/confirm/: Content-Security-Policy equal to the bundle's .htaccess (script-src
    without 'unsafe-inline'), Strict-Transport-Security max-age=31536000 without includeSubDomains or preload;
  - http://parkio.dev/ redirects to https; an unknown path answers 404;
  - the live www -> apex redirect kept in the bundle's .htaccess: https://www.parkio.dev/ and
    https://www.parkio.dev/privacy/?d1v=<stamp> answer 301 to the same path and query on https://parkio.dev (the unique
    query makes the CDN ask the origin); whether that 301 carries HSTS is reported, not gated.
No submission is made (that would write a row and send e-mail). Writes D1-verify-<stamp>.txt/.json into
~/parkio-release-20261009 (mode 600); T1 requires a PASS record before D3.
"""
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

REVIEWED_HEAD = "074d9c96bce0f2224f8bef11dee4ffef5c4dd566"  # PR #331; the bundle is built from its merge
BASE = os.environ.get("D1V_BASE", "https://parkio.dev")
HTTP_BASE = os.environ.get("D1V_HTTP_BASE", "http://parkio.dev")
WWW_BASE = os.environ.get("D1V_WWW_BASE", "https://www.parkio.dev")
APEX = "https://parkio.dev"  # the redirect target written in .htaccess
OUT = os.environ.get("D1V_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009"))
TAG = "w01n1"
EXPECTED = """
e82dccfe0a61054ee3a973239610e20b32efbe3fa1d585ed45bf724c0ac2fe65  ./.htaccess
117e726a937d38600e8fee5c571171aeb3b78206221e41e6e957fa7a133502b5  ./404.html
bdee3e60f199540842f734cd63aca422c54502c77342b28702a3341aedc568fe  ./assets/favicon-180.png
d09ea50e8048139d03ad4a47c3e5f9a207130a35a6fbbd5606ad6a158487e6eb  ./assets/favicon-32.png
be55e3a12341e701c98594a13e7ff91713b0a0410d13ed09e2edab398ffb748b  ./assets/favicon-512.png
8b8380632964bbe3d93222275a220c73e2189e5033c7341db3b0a464377cba13  ./assets/favicon-64.png
e5e129ddb788f5efcad9197022fbef860ce0a3091a36bffbb2f58954c187f37b  ./assets/parkio-logo.png
7372973d8c53e54f457b261b5b2a1f419a9b4a66aeb3737a1a62117506c5c2e7  ./assets/social-preview.png
95c3011c4abdf7d98e10c8def6454486d8fb220008a2a410b06e1a94aa87b6b8  ./i18n.js
a240aec5cdeac3581124a83a80f4a7e8379faf0b7127c3b7d7f7addac6165827  ./index.html
e32078739b21762388c335f406c9303f277b61edea4f601cc9af259435ee8688  ./privacy/index.html
31101f8c25b5e17b83edaa10bae8b2e123bc2332e4314a3efe678be59c39c61e  ./robots.txt
e95673d09e602328fe3997456acd01f9334d0db4d0f44d148e960f863e7ac931  ./site.webmanifest
29ddf83515d4f67515c54dab3ec776250442ffb46c7d0b0cfc5cfb501666b40f  ./sitemap.xml
3aee646ef3d4d9312b9c8c258200b3d2e73822413afb02866d910af4fa06dec7  ./styles.css
0990a85840b4b1320545e664c334b0a828e4331ec6332a7add2821d29d5f0cb3  ./terms/index.html
906655924d0df830ad2a64b5f55a5086239b3fc4c805ed2c6256aa5830a848a4  ./waitlist.js
935bb4d5a4d1fdff000687c925213c03a8188457a1add2c74f32f0c0e4e8eb73  ./waitlist/confirm/index.html
ee9f3de73ea3e7a794f7b33c7480d8a6181ac59493c47ebb19dcb8feaac86f87  ./waitlist/unsubscribe/index.html
"""
EXPECTED_CSP = "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self' https://api.parkio.dev; object-src 'none'; base-uri 'self'; frame-ancestors 'self'"
EXPECTED_HSTS = "max-age=31536000"
UA = {"User-Agent": "parkio-release-d1-verify/1"}  # no cache-control: see what visitors get

REPORT = []
RECORD = {"tool": "d1-verify", "reviewedHead": REVIEWED_HEAD, "base": BASE}
GATES = []
STAMP = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def say(line=""):
    print(line, flush=True)
    REPORT.append(line)


def gate(name, ok, detail=""):
    GATES.append((name, ok))
    say(f"  {'PASS' if ok else 'FAIL'} {name}{': ' + detail if detail else ''}")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def fetch(url, follow=True):
    opener = urllib.request.build_opener() if follow else urllib.request.build_opener(NoRedirect)
    req = urllib.request.Request(url, headers=UA)
    try:
        with opener.open(req, timeout=20) as resp:  # nosec - fixed public URLs
            return resp.status, {k.lower(): v for k, v in resp.headers.items()}, resp.read(5_000_000)
    except urllib.error.HTTPError as err:
        return err.code, {k.lower(): v for k, v in err.headers.items()}, err.read(200_000)
    except (urllib.error.URLError, OSError) as err:
        return None, {}, str(getattr(err, "reason", err)).encode()


def served_path(name):
    return "/" + name


def main():
    say(f"D1 verify | stamp {STAMP} | {BASE} | bundle of PR #331 head {REVIEWED_HEAD[:12]} (merged)")
    want = {}
    for line in EXPECTED.strip().splitlines():
        digest, name = line.split(None, 1)
        name = name.strip()
        want[name[2:] if name.startswith("./") else name] = digest
    say("")
    say("A. Files (SHA-256 as served; .htaccess is checked through the headers)")
    matched, bodies = 0, {}
    for name in sorted(want):
        if name == ".htaccess":
            continue
        if name.endswith(".png"):
            # Hostinger's CDN re-encodes images (WebP, downscaling, EXIF), so served bytes are not the origin bytes;
            # the origin bytes were checked against the backup before the upload (d1-preupload-check.py).
            status, headers, _ = fetch(BASE + served_path(name))
            ok = status == 200 and headers.get("content-type", "").startswith("image/")
            matched += 1 if ok else 0
            if not ok:
                say(f"  FAIL /{name}: status {status}, content-type {headers.get('content-type', 'none')}")
            continue
        urls = [BASE + served_path(name)]
        if name in ("waitlist.js", "i18n.js", "styles.css"):
            urls.append(BASE + served_path(name) + f"?v={TAG}")
        ok_all = True
        for url in urls:
            status, _, body = fetch(url)
            raw = hashlib.sha256(body).hexdigest() if status == 200 else None
            lf = hashlib.sha256(body.replace(b"\r\n", b"\n")).hexdigest() if status == 200 else None
            ok = status == 200 and want[name] in (raw, lf)
            ok_all = ok_all and ok
            if not ok:
                say(f"  FAIL {url[len(BASE):]}: status {status}, sha256 {'differs' if status == 200 else '-'}")
            bodies[name] = body
        matched += 1 if ok_all else 0
    files_total = len([n for n in want if n != ".htaccess"])
    gate("every bundle file is served as built (images: present, re-encoded by the CDN)", matched == files_total, f"{matched}/{files_total}")
    say("")
    say("B. Landing page and waitlist client")
    index = bodies.get("index.html", b"").decode("utf-8", "replace")
    meta = re.search(r'<meta\s+name="parkio-waitlist-mode"\s+content="([a-z]+)"', index)
    gate("landing meta parkio-waitlist-mode=api", bool(meta) and meta.group(1) == "api",
         meta.group(1) if meta else "absent")
    refs = re.findall(r'(?:href|src)="/(styles\.css|i18n\.js|waitlist\.js)(\?v=[a-z0-9]+)?"', index)
    gate(f"landing loads the shared assets with ?v={TAG}", len(refs) == 3 and all(r[1] == f"?v={TAG}" for r in refs),
         ", ".join(f"{a}{v or ' (no buster)'}" for a, v in refs))
    gate("waitlist.js carries waitlist-consent-v1", b"waitlist-consent-v1" in bodies.get("waitlist.js", b""))
    say("")
    say("C. Headers, redirect, 404")
    for path in ("/", "/waitlist/confirm/"):
        status, headers, _ = fetch(BASE + path)
        csp = headers.get("content-security-policy", "")
        hsts = headers.get("strict-transport-security", "")
        gate(f"{path} status 200", status == 200, str(status))
        gate(f"{path} CSP equals the bundle's .htaccess", csp == EXPECTED_CSP,
             "equal" if csp == EXPECTED_CSP else ("absent" if not csp else "differs"))
        gate(f"{path} script-src without 'unsafe-inline'", bool(csp) and "'unsafe-inline'" not in
             next((d for d in csp.split(";") if d.strip().startswith("script-src")), "'unsafe-inline'"))
        gate(f"{path} HSTS {EXPECTED_HSTS}", hsts.strip() == EXPECTED_HSTS, hsts.strip() or "absent")
    status, headers, _ = fetch(HTTP_BASE + "/", follow=False)
    location = headers.get("location", "")
    gate("http:// redirects to https", status in (301, 302, 307, 308) and location.startswith("https://"),
         f"{status} -> {'https' if location.startswith('https://') else (location.split(':', 1)[0] or 'none')}")
    status, _, _ = fetch(BASE + "/d1-verify-no-such-page")
    gate("unknown path answers 404", status == 404, str(status))
    for path, name in (("/", "www.parkio.dev/ answers 301 to https://parkio.dev/"),
                       (f"/privacy/?d1v={STAMP.lower()}",
                        "www.parkio.dev/privacy/?d1v=<stamp> answers 301 to the same path and query on parkio.dev")):
        status, headers, _ = fetch(WWW_BASE + path, follow=False)
        location = headers.get("location", "")
        gate(name, status == 301 and location == APEX + path, f"{status} -> {location or 'no location'}")
    say(f"  info: HSTS on that www 301: {headers.get('strict-transport-security', '').strip() or 'absent'}; "
        f"CDN cache status: {headers.get('x-hcdn-cache-status', 'n/a')}")
    failed = [n for n, ok in GATES if not ok]
    RECORD.update(stamp=STAMP, gates={n: ok for n, ok in GATES}, result="PASS" if not failed else "FAIL")
    say("")
    say(f"gates passed {len(GATES) - len(failed)}/{len(GATES)}" + (f"; failed: {', '.join(failed)}" if failed else ""))
    say("D1 VERIFIED (live)" if not failed else "D1 NOT VERIFIED")
    os.makedirs(OUT, mode=0o700, exist_ok=True)
    for suffix, text in ((".txt", "\n".join(REPORT) + "\n"), (".json", json.dumps(RECORD, indent=2, sort_keys=True) + "\n")):
        path = os.path.join(OUT, f"D1-verify-{STAMP}{suffix}")
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"saved: {path}")
    sys.exit(0 if not failed else 1)


if __name__ == "__main__":
    main()
