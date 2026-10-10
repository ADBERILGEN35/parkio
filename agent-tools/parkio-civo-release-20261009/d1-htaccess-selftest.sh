#!/usr/bin/env bash
# Local self-test of d1-htaccess-inspect.py on synthetic backups (zip without a public_html/ prefix, like the owner's):
# V1 the older known version 34b8af43; V2 the recorded version with CRLF and a BOM (formatting only); V3 the recorded
# version plus sensitive lines (SetEnv, php_value, an escaped-IP RewriteCond, a query token, a custom header, Require ip,
# AuthUserFile, a comment with an e-mail and a token) that must never be printed; V4 the release version.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/d1-htaccess-inspect.py"; REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mkzip() {  # name htaccess-file
  rm -rf "$W/site" && mkdir -p "$W/site/waitlist/confirm" && echo x > "$W/site/index.html" && echo x > "$W/site/waitlist/confirm/index.html"
  cp "$2" "$W/site/.htaccess"; (cd "$W/site" && zip -qr "$W/$1.zip" .)
}
git -C "$REPO" cat-file -p 34b8af43b4b1dafce567e338e195377a3ccff173 > "$W/v1"
git -C "$REPO" cat-file -p d3b6360d725b6d6473dc047fd2afbc89b455eb46 > "$W/rec"
git -C "$REPO" cat-file -p 87aec6ec1699aef86274169b0f8573ff0496b721 > "$W/v4"
{ printf '\xef\xbb\xbf'; sed 's/$/\r/' "$W/rec"; } > "$W/v2"
cp "$W/rec" "$W/v3"; cat >> "$W/v3" <<'H'
# contact: ops-person@example.org token AbCdEfGhIjKlMnOpQrStUvWxYz123456
SetEnv API_KEY s3cr3t-Value-ABCDEFGHIJKLMNOP
php_value auto_prepend_file /home/u123/hidden.php
<IfModule mod_rewrite.c>
  RewriteEngine On
  RewriteCond %{REMOTE_ADDR} !^203\.0\.113\.7$
  RewriteRule ^admin - [F]
  RewriteRule ^go$ https://example.com/?key=SuperSecretQueryValue123 [R=302,L]
</IfModule>
Header set X-Api-Key "hdr-secret-value-123"
Require ip 198.51.100.4
AuthUserFile /home/u123/.htpasswd
H
for v in v1 v2 v3 v4; do mkzip "$v" "$W/$v"; python3 -I "$TOOL" "$W/$v.zip" > "$W/$v.out" 2>&1 || { echo "FAIL $v exit"; exit 1; }; done
sed -n '1,60p' "$W/v3.out"
fail=0
check() { if grep -qF -- "$2" "$W/$1.out"; then echo "ok   $1: $2"; else echo "FAIL $1: $2"; fail=1; fi; }
check v1 "equals a known version: 34b8af43 = ad384269 (2026-09-21)"
check v1 "equals the recorded version d3b6360d ignoring line endings, trailing spaces and blank lines: no"
check v2 "CRLF lines 26; BOM yes"
check v2 "equals the recorded version d3b6360d ignoring line endings, trailing spaces and blank lines: yes"
check v2 "B1. recorded d3b6360d -> live"
check v3 "equals a known version: none"
check v3 "+ SetEnv <value redacted, line"
check v3 "+ php_value <value redacted, line"
check v3 "RewriteCond %{REMOTE_ADDR} !^<ip>$"
check v3 "RewriteRule ^go$ https://example.com/?key=<value> [R=302,L]"
check v3 "+ Header set X-Api-Key <value redacted, line"
check v3 "+ Require <value redacted, line"
check v3 "+ AuthUserFile <value redacted, line"
check v3 "# contact: <email> token <token:32>"
check v3 "SUMMARY: live lines the upload would remove:"
check v3 "+   Header always set Strict-Transport-Security \"max-age=31536000\""
check v4 "equals a known version: 87aec6ec = fbfa218f"
check v4 "(no difference)"
for secret in ops-person AbCdEfGhIjKl s3cr3t auto_prepend_file hidden.php 203 198.51 SuperSecretQueryValue hdr-secret htpasswd; do
  if grep -qF -- "$secret" "$W"/v*.out; then echo "FAIL leaked: $secret"; fail=1; fi
done
echo "ok   no secret, IP, e-mail, token or query value printed (checked 10 values)"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
