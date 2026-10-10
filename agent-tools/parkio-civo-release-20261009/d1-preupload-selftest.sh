#!/usr/bin/env bash
# Local self-test of d1-preupload-check.py: backups shaped like a Hostinger public_html download (CRLF text files as on
# the origin, extra live-only files, a public_html/ prefix) as a folder, a zip and a tar.gz. S1 recorded live state ->
# PASS (10 replaced, 9 unchanged); S2 an unrecorded edit -> FAIL; S3 an extra .htaccess directive -> FAIL; S4 a file
# already at the new version -> PASS. Needs the recorded-live and PR #330 trees (git archive of db3f4a3d plus the two
# known live edits, and of 272273c4).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/d1-preupload-check.py"; REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mkdir -p "$W/live/public_html" "$W/head"
git -C "$REPO" archive db3f4a3d web/marketing | tar -x -C "$W/live"
git -C "$REPO" archive 272273c405c69beec94ce63913e7ddfd803595c3 web/marketing | tar -x -C "$W/head"
cp -a "$W/live/web/marketing/." "$W/live/public_html/"; rm -rf "$W/live/web"
find "$W/live/public_html" -maxdepth 1 -name '*.test.mjs' -delete   # as packaged
# the two recorded hand edits on parkio.dev (cache-buster and TR/EN success text)
sed -i 's#/i18n.js?v=w01l7#/i18n.js?v=w01m2#' "$W/live/public_html/index.html"
python3 - "$W/live/public_html/i18n.js" <<'PY'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
s = s.replace("'Teşekkürler. Adresiniz alındı. Listede kalmak için e-postanızdaki onay bağlantısını açıp Onayla’ya basın.',",
              "'Teşekkürler! İlk kez katılıyorsanız e-postanıza gelen bağlantıyla adresinizi doğrulayın. Daha önce onayladıysanız tekrar işlem yapmanıza gerek yok.',")
s = s.replace("'Thanks. Your address was received. Open the confirmation link in your email and press Confirm to stay on the list.',",
              "'Thank you! If this is your first time joining, verify your email using the link sent to your inbox. If you have already confirmed, no further action is needed.',")
open(p, "w", encoding="utf-8").write(s)
PY
for f in index.html i18n.js privacy/index.html; do sed -i 's/$/\r/' "$W/live/public_html/$f"; done   # origin CRLF
echo '<!-- google site verification -->' > "$W/live/public_html/google1234.html"   # live-only files
cp "$W/head/web/marketing/waitlist.adapter.test.mjs" "$W/live/public_html/" 2>/dev/null || true
fail=0
run() { python3 -I "$TOOL" "$1" > "$W/$2" 2>&1 && echo 0 > "$W/$2.rc" || echo $? > "$W/$2.rc"; }
check() { if grep -qF -- "$2" "$W/$1"; then echo "ok   $1: $2"; else echo "FAIL $1: $2"; fail=1; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && echo "ok   $1 exit $2" || { echo "FAIL $1 exit $(cat "$W/$1.rc"), want $2"; fail=1; }; }
(cd "$W/live" && zip -qr "$W/backup.zip" public_html && tar -czf "$W/backup.tgz" public_html)
for form in "$W/live/public_html" "$W/backup.zip" "$W/backup.tgz"; do
  n="s1-$(basename "$form" | tr '.' '-')"; run "$form" "$n"; rc_is "$n" 0
  check "$n" "PRE-UPLOAD CHECK PASS"
  check "$n" ".htaccess: recorded live state; the upload replaces it"
  check "$n" "assets/social-preview.png: recorded live state; unchanged by the upload"
  check "$n" "index.html: recorded live state; the upload replaces it"
  check "$n" "files only on live (extraction leaves them as they are): 2: google1234.html, waitlist.adapter.test.mjs"
  [ "$(grep -c 'the upload replaces it' "$W/$n")" = 10 ] && echo "ok   $n: 10 replaced" || { echo "FAIL $n replaced count"; fail=1; }
  [ "$(grep -c 'unchanged by the upload' "$W/$n")" = 9 ] && echo "ok   $n: 9 unchanged" || { echo "FAIL $n unchanged count"; fail=1; }
done
sed -n '1,30p' "$W/s1-backup-zip"
cp -a "$W/live" "$W/live2"; echo '<p>hand edit</p>' >> "$W/live2/public_html/terms/index.html"
run "$W/live2" s2; rc_is s2 1; check s2 "terms/index.html: UNRECORDED LIVE CHANGE"; check s2 "PRE-UPLOAD CHECK FAIL"
cp -a "$W/live" "$W/live3"; echo 'Redirect 301 /old https://parkio.dev/' >> "$W/live3/public_html/.htaccess"
run "$W/live3" s3; rc_is s3 1; check s3 ".htaccess: UNRECORDED LIVE CHANGE"
cp -a "$W/live" "$W/live4"; cp "$W/head/web/marketing/styles.css" "$W/live4/public_html/styles.css"
run "$W/live4" s4; rc_is s4 0; check s4 "styles.css: already the new version"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
