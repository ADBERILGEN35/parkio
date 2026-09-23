#!/usr/bin/env python3
"""HTTP acceptance against the running nginx image, with no API calls."""

import re
import sys
from urllib.request import urlopen

base = sys.argv[1].rstrip("/")
css_asset = sys.argv[2]
security = (
    "content-security-policy",
    "referrer-policy",
    "x-content-type-options",
    "x-frame-options",
    "permissions-policy",
)


def get(path):
    with urlopen(base + path, timeout=15) as response:
        body = response.read()
        assert response.status == 200, (path, response.status)
        headers = response.headers
        for name in security:
            assert headers.get(name), (path, name)
        print(f"PASS {path} HTTP 200 security headers")
        return body, headers


index, html_headers = get("/")
assert b'id="root"' in index
for path in (
    "/index.html",
    "/explore",
    "/explore/index.html",
    "/login",
    "/map",
    "/admin/waitlist",
    "/register?lang=tr",
    "/register?lang=en",
):
    body, headers = get(path)
    assert b"<html" in body.lower(), path
    if path in ("/index.html", "/explore/index.html"):
        assert "no-cache" in headers.get("cache-control", ""), path
    if path == "/explore":
        assert b"Public parking explore" in body, path
assert "no-cache" in html_headers.get("cache-control", "")

assets = re.findall(rb'(?:src|href)="(/assets/[^" ]+\.(?:js|css))"', index)
assert assets, "no JS/CSS assets in index"
assert any(asset.endswith(b".js") for asset in assets)
assert css_asset.startswith("/assets/") and css_asset.endswith(".css")
assets.append(css_asset.encode())
for asset in assets:
    path = asset.decode()
    body, headers = get(path)
    assert body, path
    expected = "javascript" if path.endswith(".js") else "css"
    assert expected in headers.get("content-type", ""), path
    assert "immutable" in headers.get("cache-control", ""), path

for path in ("/sw.js", "/manifest.webmanifest"):
    _, headers = get(path)
    assert "no-cache" in headers.get("cache-control", ""), path

print("HTTP_ACCEPTANCE_OK")
