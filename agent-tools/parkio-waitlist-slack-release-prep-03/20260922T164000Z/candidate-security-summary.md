# Final code candidate identity and security summary

Source revision: `008a6b2077f64d82a83da6d88e7bb6efeeafb498`

Local image: `parkio-gateway-waitlist-candidate:008a6b2077f6`

Image/index id and local digest:
`sha256:16f7d5249763008c0856c0c1c3310eb8b17137ae50069b99dc09a489504651a1`

Platform: `linux/amd64`; configured user: `parkio`; OCI revision matches the
source revision. This image was built locally from
`services/gateway-service/Dockerfile` and was not pushed.

Trivy `v1.22.0` scanned the exact image with the vulnerability scanner and no
`--ignore-unfixed`:

| Scope | UNKNOWN | LOW | MEDIUM | HIGH | CRITICAL | Total |
|---|---:|---:|---:|---:|---:|---:|
| Ubuntu 26.04 packages | 0 | 4 | 69 | 0 | 0 | 73 |
| `app.jar` | 0 | 0 | 7 | 0 | 0 | 7 |
| `/usr/bin/pebble` | 0 | 0 | 0 | 0 | 0 | 0 |
| **All targets** | **0** | **4** | **76** | **0** | **0** | **80** |

Unfixed: 61 package occurrences (`57 MEDIUM`, `4 LOW`, 47 unique advisory
ids). Fixed version available: 19 occurrences (18 unique advisory ids). The
HIGH/CRITICAL policy view is clean, including unfixed findings. MEDIUM/LOW
findings remain disclosed and are not described as absent.

The eventual release-built digest is not this local digest and requires a
fresh exact-image scan.
