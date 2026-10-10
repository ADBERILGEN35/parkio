#!/usr/bin/env bash
# D1 marketing package - owner workstation, Windows Terminal -> Ubuntu (WSL). Approved decision M2 (2026-10-10).
#
#   bash /tmp/d1-marketing-package.sh
#
# Builds the Hostinger launch bundle from the merge of PR #331's reviewed head (REVIEWED_HEAD below; PR #330's M2 bundle plus
# the live www -> apex redirect in .htaccess) into origin/api: the merge commit whose second parent is that head and whose
# web/marketing tree equals the reviewed one. It uses a fresh
# sparse clone under /tmp and the repository's own packager (scripts/package-marketing-waitlist-bundles.sh launch).
# It does not touch any existing checkout (the backend release checkout on Civo stays at 22f96990) and uploads nothing.
# Checks: the revision is on origin/api; the packager produced a real zip and its MANIFEST names that revision and
# landing meta api; the zip holds exactly the expected files with the expected SHA-256 (embedded below, computed from the
# same revision); waitlist.js carries waitlist-consent-v1; every page loads the shared assets with ?v=w01n1.
# Output: ~/parkio-marketing-<sha12>/ with the zip, the packager's MANIFEST and VERIFY.txt. The clone is removed.
set -euo pipefail
REVIEWED_HEAD=074d9c96bce0f2224f8bef11dee4ffef5c4dd566
REPO_URL="${D1P_REPO_URL:-https://github.com/ADBERILGEN35/parkio.git}"
TAG=w01n1
command -v git >/dev/null && command -v python3 >/dev/null && command -v zip >/dev/null \
  || { echo "STOP: git, python3 and zip are required (zip: sudo apt install zip)"; exit 2; }
WORK="$(mktemp -d /tmp/parkio-mkt-XXXXXX)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT
echo "D1 marketing package | reviewed head $REVIEWED_HEAD"
git clone -q --filter=blob:none --no-checkout --sparse "$REPO_URL" "$WORK/repo"
git -C "$WORK/repo" sparse-checkout set web/marketing scripts
MERGE_SHA="$(git -C "$WORK/repo" log --merges --format='%H %P' origin/api | awk -v h="$REVIEWED_HEAD" '$3 == h && !found {print $1; found = 1}')"
[ -n "$MERGE_SHA" ] || { echo "STOP: the reviewed head $REVIEWED_HEAD is not merged into origin/api yet"; exit 1; }
[ "$(git -C "$WORK/repo" rev-parse "$MERGE_SHA:web/marketing")" = "$(git -C "$WORK/repo" rev-parse "$REVIEWED_HEAD:web/marketing")" ] \
  || { echo "STOP: the merge's web/marketing differs from the reviewed head"; exit 1; }
git -C "$WORK/repo" checkout -q --detach "$MERGE_SHA"
[ "$(git -C "$WORK/repo" rev-parse HEAD)" = "$MERGE_SHA" ] || { echo "STOP: checkout is not $MERGE_SHA"; exit 1; }
OUT="$HOME/parkio-marketing-${MERGE_SHA:0:12}"
[ ! -e "$OUT" ] || { echo "STOP: $OUT exists; move it away first"; exit 2; }
echo "  merge commit $MERGE_SHA on origin/api (second parent = reviewed head; web/marketing tree identical)"
echo "  output $OUT"
mkdir -p "$OUT"
bash "$WORK/repo/scripts/package-marketing-waitlist-bundles.sh" launch "$OUT" > "$WORK/packager.out"
ZIP="$OUT/parkio-marketing-launch-${MERGE_SHA:0:12}.zip"
[ -f "$ZIP" ] || { echo "STOP: the packager did not write $ZIP"; exit 1; }
rc=0
python3 -I - "$ZIP" "$OUT/parkio-marketing-launch-${MERGE_SHA:0:12}.MANIFEST.txt" "$MERGE_SHA" "$TAG" > "$OUT/VERIFY.txt" <<'PY' || rc=$?
import hashlib, re, sys, zipfile
zpath, manifest_path, sha, tag = sys.argv[1:5]
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
want = {}
for line in EXPECTED.strip().splitlines():
    digest, name = line.split(None, 1)
    want[name.strip()[2:] if name.strip().startswith("./") else name.strip()] = digest
problems = []
if not zipfile.is_zipfile(zpath):
    print("STOP: the archive is not a zip file")
    sys.exit(1)
got = {}
with zipfile.ZipFile(zpath) as z:
    for info in z.infolist():
        if info.is_dir():
            continue
        name = info.filename[2:] if info.filename.startswith("./") else info.filename
        got[name] = hashlib.sha256(z.read(info)).hexdigest()
    files = {n: z.read(i).decode("utf-8", "replace") for i in z.infolist() if not i.is_dir()
             for n in [i.filename[2:] if i.filename.startswith("./") else i.filename] if n.endswith((".html", ".js"))}
missing = sorted(set(want) - set(got)); extra = sorted(set(got) - set(want))
differ = sorted(n for n in set(want) & set(got) if want[n] != got[n])
for label, items in (("missing", missing), ("unexpected", extra), ("different", differ)):
    if items:
        problems.append(f"{label}: {', '.join(items)}")
manifest = open(manifest_path, encoding="utf-8").read()
if f"source_sha={sha}" not in manifest or 'landing_meta=parkio-waitlist-mode" content="api"' not in manifest or "mode=launch" not in manifest:
    problems.append("packager MANIFEST does not name this revision, launch mode and landing meta api")
if "waitlist-consent-v1" not in files.get("waitlist.js", ""):
    problems.append("waitlist.js does not carry waitlist-consent-v1")
for name, text in files.items():
    if name.endswith(".html"):
        for ref in re.findall(r'(?:href|src)="/(?:styles\.css|i18n\.js|waitlist\.js)(\?v=[a-z0-9]+)?"', text):
            if ref != "?v=" + tag:
                problems.append(f"{name} loads a shared asset with {ref or 'no buster'}")
print(f"files: {len(got)} in the zip, {len(want)} expected; matching: {len(set(want) & set(got)) - len(differ)}")
print(f"zip sha256: {hashlib.sha256(open(zpath, 'rb').read()).hexdigest()}")
for p in problems:
    print(f"FAIL {p}")
print("PACKAGE VERIFIED" if not problems else "PACKAGE NOT VERIFIED")
sys.exit(1 if problems else 0)
PY
cat "$OUT/VERIFY.txt"
echo "  output: $(ls -1 "$OUT" | tr '\n' ' ')"
exit "$rc"
