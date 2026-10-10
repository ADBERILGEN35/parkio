#!/usr/bin/env bash
# Local self-test of d1-verify.py against a local server that serves the PR #330 bundle with the bundle's CSP and HSTS
# headers, re-encodes PNGs (as Hostinger's CDN does), answers 404 for unknown paths, plus a second port that redirects
# http -> https. S1 -> D1 VERIFIED; S2 a stale waitlist.js -> FAIL; S3 no HSTS -> FAIL. Public GETs only in production.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/d1-verify.py"; REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
W="$(mktemp -d)"; P1=18761; P2=18762; SRV=""; SRV2=""
cleanup() { [ -n "$SRV" ] && kill "$SRV" 2>/dev/null || true; [ -n "$SRV2" ] && kill "$SRV2" 2>/dev/null || true; rm -rf "$W"; }
trap cleanup EXIT
git -C "$REPO" archive 272273c405c69beec94ce63913e7ddfd803595c3 web/marketing | tar -x -C "$W"
SITE="$W/web/marketing"; find "$SITE" -maxdepth 1 -name '*.test.mjs' -delete
cat > "$W/serve.py" <<'PY'
import http.server, os, re, sys
site, port, mode = sys.argv[1], int(sys.argv[2]), sys.argv[3]
ht = open(os.path.join(site, ".htaccess"), encoding="utf-8").read()
CSP = re.search(r'Content-Security-Policy "([^"]+)"', ht).group(1)
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        if mode == "redirect":
            self.send_response(301); self.send_header("Location", "https://parkio.dev" + self.path); self.end_headers(); return
        path = self.path.split("?", 1)[0]
        if path.endswith("/"):
            path += "index.html"
        f = os.path.join(site, path.lstrip("/"))
        if not os.path.isfile(f) or path.endswith(".htaccess"):
            body = open(os.path.join(site, "404.html"), "rb").read(); self.send_response(404)
        else:
            body = open(f, "rb").read()
            if f.endswith(".png"):
                body = b"RIFF-webp-reencoded" + body[:64]
            if mode == "stale" and f.endswith("waitlist.js"):
                body = body.replace(b"waitlist-consent-v1", b"")
            self.send_response(200)
        ctype = "image/webp" if path.endswith(".png") else ("text/html" if path.endswith(".html") else "application/octet-stream")
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Security-Policy", CSP)
        if mode != "nohsts":
            self.send_header("Strict-Transport-Security", "max-age=31536000")
        self.end_headers(); self.wfile.write(body)
http.server.ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
PY
start() { python3 "$W/serve.py" "$SITE" "$P1" "$1" & SRV=$!; sleep 1; }
python3 "$W/serve.py" "$SITE" "$P2" redirect & SRV2=$!
fail=0
check() { if grep -qF -- "$2" "$W/$1"; then echo "ok   $1: $2"; else echo "FAIL $1: $2"; fail=1; fi; }
runv() { D1V_BASE="http://127.0.0.1:$P1" D1V_HTTP_BASE="http://127.0.0.1:$P2" D1V_OUT="$W/out" python3 -I "$TOOL" > "$W/$1" 2>&1 \
         && echo 0 > "$W/$1.rc" || echo $? > "$W/$1.rc"; }
start ok; runv s1; kill "$SRV"; SRV=""
sed "s#$W#<W>#g" "$W/s1"
[ "$(cat "$W/s1.rc")" = 0 ] && echo "ok   s1 exit 0" || { echo "FAIL s1 exit"; fail=1; }
check s1 "PASS every bundle file is served as built (images: present, re-encoded by the CDN): 18/18"
check s1 "PASS landing meta parkio-waitlist-mode=api"
check s1 "PASS landing loads the shared assets with ?v=w01n1"
check s1 "PASS waitlist.js carries waitlist-consent-v1"
check s1 "PASS / CSP equals the bundle's .htaccess: equal"
check s1 "PASS /waitlist/confirm/ HSTS max-age=31536000"
check s1 "PASS http:// redirects to https: 301 -> https"
check s1 "PASS unknown path answers 404: 404"
check s1 "D1 VERIFIED (live)"
start stale; runv s2; kill "$SRV"; SRV=""
[ "$(cat "$W/s2.rc")" = 1 ] && echo "ok   s2 exit 1" || { echo "FAIL s2 exit"; fail=1; }
check s2 "FAIL /waitlist.js: status 200, sha256 differs"
check s2 "FAIL waitlist.js carries waitlist-consent-v1"
check s2 "D1 NOT VERIFIED"
start nohsts; runv s3; kill "$SRV"; SRV=""
[ "$(cat "$W/s3.rc")" = 1 ] && echo "ok   s3 exit 1" || { echo "FAIL s3 exit"; fail=1; }
check s3 "FAIL / HSTS max-age=31536000: absent"
modes="$(stat -c %a "$W"/out/* | sort -u | tr '\n' ' ')"; [ "$modes" = "600 " ] && echo "ok   reports mode 600" || { echo "FAIL modes $modes"; fail=1; }
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
