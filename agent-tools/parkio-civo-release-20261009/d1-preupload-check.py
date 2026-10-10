#!/usr/bin/env python3
"""D1 pre-upload check - would the M2 upload overwrite anything on parkio.dev that no record explains? (read-only)

Owner workstation, Windows Terminal -> Ubuntu (WSL), after downloading the public_html backup from Hostinger:
  python3 -I /tmp/d1-preupload-check.py <backup>     (<backup> = the downloaded .zip / .tar.gz / .tgz, or an extracted folder)

For each of the 19 bundle paths it compares the ORIGIN bytes in the backup (CRLF -> LF for text files) with:
  - the recorded live state: PR #93's tree (db3f4a3d) plus the two known hand edits on parkio.dev (index.html's
    i18n.js?v=w01m2 and i18n.js's TR/EN waitlist.success text), as served on 2026-10-10;
  - the bundle built from PR #330's reviewed tree.
A file that matches neither is an unrecorded live change: the upload would overwrite it, so the check fails and nothing
should be uploaded until the owner decides. Files that exist only in the backup are listed by name; extracting the bundle
does not touch them. It also settles the two images the CDN re-encodes (origin bytes vs repo) and the live .htaccess
(origin file vs the recorded d3b6360d). It reads only the backup, writes nothing and prints no file content.
"""
import hashlib
import io
import os
import sys
import tarfile
import zipfile

RECORDED_LIVE = """26296cb6265bb7b09af9dce104838643d7921fdb21c12fcb91f076541fd3ede2  ./.htaccess
94864ecff437bc90a148e18e1a2d5679ebd56f56a957c1229b139043d8dba94c  ./404.html
bdee3e60f199540842f734cd63aca422c54502c77342b28702a3341aedc568fe  ./assets/favicon-180.png
d09ea50e8048139d03ad4a47c3e5f9a207130a35a6fbbd5606ad6a158487e6eb  ./assets/favicon-32.png
be55e3a12341e701c98594a13e7ff91713b0a0410d13ed09e2edab398ffb748b  ./assets/favicon-512.png
8b8380632964bbe3d93222275a220c73e2189e5033c7341db3b0a464377cba13  ./assets/favicon-64.png
e5e129ddb788f5efcad9197022fbef860ce0a3091a36bffbb2f58954c187f37b  ./assets/parkio-logo.png
7372973d8c53e54f457b261b5b2a1f419a9b4a66aeb3737a1a62117506c5c2e7  ./assets/social-preview.png
064511e3929a412affdc5d0c2eb108ae79f3ef02f7c7edb4f5b4a4fbf51fd965  ./i18n.js
bec7e1e6a32af9b7e476ee4aee304e148b5d6fa39e6d4d0dcdb8f04c98566b50  ./index.html
831d2d2691911467cd774c9706cf4455922403620081db2bd7b79f1c5abc8c65  ./privacy/index.html
31101f8c25b5e17b83edaa10bae8b2e123bc2332e4314a3efe678be59c39c61e  ./robots.txt
e95673d09e602328fe3997456acd01f9334d0db4d0f44d148e960f863e7ac931  ./site.webmanifest
29ddf83515d4f67515c54dab3ec776250442ffb46c7d0b0cfc5cfb501666b40f  ./sitemap.xml
3fecab26e089a3f954c52502b89b94b63397de221d3caf485ed4a782ce863dce  ./styles.css
3bd15878f4c1c3928722cfb0ddf5b1e8232307cfebe4c7d3f4f9c6193e4e8403  ./terms/index.html
1a7d07e78ca4e189c9d0507db2c556df6e9526362d729a9341900b08b83fa9ec  ./waitlist.js
ebc4df43c3d3a8f0699d57664f813ef3620b3df1ebf6e6a9b397145c7f719861  ./waitlist/confirm/index.html
101b6b0d17c8104fbb291946739fa386dca66d9bfa50d9fab7a0a02f738e6795  ./waitlist/unsubscribe/index.html"""
BUNDLE = """f1afadbb3c4287e631621f2d5036425ef44cb5ff4738a7509f648a4cae9185d0  ./.htaccess
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
ee9f3de73ea3e7a794f7b33c7480d8a6181ac59493c47ebb19dcb8feaac86f87  ./waitlist/unsubscribe/index.html"""
TEXT = (".html", ".js", ".css", ".txt", ".xml", ".webmanifest", ".htaccess")


def table(text):
    out = {}
    for line in text.strip().splitlines():
        digest, name = line.split(None, 1)
        out[name.strip()[2:]] = digest
    return out


def digest(name, data):
    if name.endswith(TEXT):
        data = data.replace(b"\r\n", b"\n")
    return hashlib.sha256(data).hexdigest()


def read_backup(path):
    files = {}
    if os.path.isdir(path):
        for dp, _, fn in os.walk(path):
            for f in fn:
                p = os.path.join(dp, f)
                with open(p, "rb") as fh:
                    files[os.path.relpath(p, path).replace(os.sep, "/")] = fh.read()
    elif zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                if not info.is_dir():
                    files[info.filename] = z.read(info)
    elif tarfile.is_tarfile(path):
        with tarfile.open(path) as t:
            for m in t.getmembers():
                if m.isfile():
                    files[m.name] = t.extractfile(m).read()
    else:
        print("STOP: the backup is not a folder, a zip or a tar archive")
        sys.exit(2)
    clean = {}
    for name, data in files.items():
        while name.startswith("./"):
            name = name[2:]
        clean[name.lstrip("/")] = data
    prefixes = [n[: -len("waitlist/confirm/index.html")] for n in clean if n.endswith("waitlist/confirm/index.html")]
    if len(prefixes) != 1:
        print("STOP: cannot locate the site root in the backup (expected exactly one waitlist/confirm/index.html)")
        sys.exit(2)
    prefix = prefixes[0]
    return {n[len(prefix):]: d for n, d in clean.items() if n.startswith(prefix)}, prefix


def main():
    if len(sys.argv) != 2:
        print("usage: python3 -I d1-preupload-check.py <backup .zip/.tar.gz/.tgz or folder>")
        sys.exit(2)
    live, bundle = table(RECORDED_LIVE), table(BUNDLE)
    files, prefix = read_backup(sys.argv[1])
    print(f"D1 pre-upload check | backup root '{prefix or '.'}' | {len(files)} files in the backup")
    unrecorded, absent = [], []
    for name in sorted(bundle):
        if name not in files:
            absent.append(name)
            print(f"  {name}: absent on live (the upload adds it)")
            continue
        d = digest(name, files[name])
        if d == live[name]:
            state = "recorded live state" + ("; the upload replaces it" if bundle[name] != live[name] else "; unchanged by the upload")
        elif d == bundle[name]:
            state = "already the new version"
        else:
            state = "UNRECORDED LIVE CHANGE: the upload would overwrite it"
            unrecorded.append(name)
        print(f"  {name}: {state}")
    only_live = sorted(n for n in files if n not in bundle)
    print(f"  files only on live (extraction leaves them as they are): {len(only_live)}"
          + (": " + ", ".join(only_live[:40]) + (" ..." if len(only_live) > 40 else "") if only_live else ""))
    print(f"SUMMARY: {len(bundle) - len(absent)}/{len(bundle)} bundle paths present on live; unrecorded live changes: "
          f"{len(unrecorded)}{' (' + ', '.join(unrecorded) + ')' if unrecorded else ''}")
    print("PRE-UPLOAD CHECK PASS" if not unrecorded else "PRE-UPLOAD CHECK FAIL: do not upload; report the paths above")
    sys.exit(0 if not unrecorded else 1)


if __name__ == "__main__":
    main()
