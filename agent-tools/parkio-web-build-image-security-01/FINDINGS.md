# Exact HIGH/CRITICAL finding ledger

## Final remediation result

This document retains the prior builder's complete HIGH/CRITICAL ledger below. It is historical evidence, not the final scan. `HIGH-CRITICAL-DELTA.csv` maps all 63 prior CRITICAL/HIGH records to the final installed builder: 3 CRITICAL and 56 HIGH are fixed; 4 HIGH remain in Corepack pnpm 10.34.5's bundled dependencies. `FINAL-RESIDUAL-ALL.csv` lists every final finding, including exact installed version, CVE, scanner fixed version and package path. The final builder scan is **0 CRITICAL / 4 HIGH / 2 MEDIUM / 1 LOW / 0 UNKNOWN**; the final nginx runtime is zero at every severity. All seven final records have a scanner fixed version. The reason each cannot safely be advanced in this web-only change is documented in `RELEASE-PACKAGE.md`.

The esbuild Go binary finding maps to `apps/web → vite@6.4.3 → esbuild@0.25.9 → @esbuild/linux-x64@0.25.9` in the prior image. Trivy's `gobinary` target was the executed pnpm-store binary (`root/.local/share/pnpm/store/v3/files/e8/fe5565d348ec52426c93bd74e516659b5302081e641dd5859c986f5824bbc5429066d8efce0bb2542988388e01429e19023365b102604e517eea4ff725891b-exec`); Go stdlib records lack `PkgPath`, so this target supplies the binary path. The final builder installs esbuild 0.27.4 compiled with Go 1.25.7; no Go CRITICAL/HIGH record remains.

## Prior builder baseline and base-refresh evidence

All rows come from the **actual installed builder images** in `evidence/baseline-builder.json.gz` and `evidence/candidate-builder.json.gz`, scanned with Trivy 0.74.0 and one DB snapshot. A fixed version is Trivy metadata, not proof that the package can be updated independently within this web-only change. Repeated CVEs on separate packages or package copies are separate scanner records.

## Baseline Debian OS findings

The baseline builder OS result has 4 CRITICAL and 52 HIGH records. The table names the package introducing each record. The other 169 OS records are MEDIUM/LOW/UNKNOWN; all 225 OS records have no fixed version in this DB.

| Origin | Package | Installed | Severity | CVE | Trivy fixed version | Status |
| --- | --- | --- | --- | --- | --- | --- |
| Debian OS | `bsdutils` | `1:2.38.1-5+deb12u3` | HIGH | `CVE-2026-53613` | `none reported` | affected |
| Debian OS | `bsdutils` | `1:2.38.1-5+deb12u3` | HIGH | `CVE-2026-76642` | `none reported` | affected |
| Debian OS | `bsdutils` | `1:2.38.1-5+deb12u3` | HIGH | `CVE-2026-78408` | `none reported` | affected |
| Debian OS | `bsdutils` | `1:2.38.1-5+deb12u3` | HIGH | `CVE-2026-78409` | `none reported` | affected |
| Debian OS | `bsdutils` | `1:2.38.1-5+deb12u3` | HIGH | `CVE-2026-78410` | `none reported` | affected |
| Debian OS | `gzip` | `1.12-1` | HIGH | `CVE-2026-41992` | `none reported` | fix_deferred |
| Debian OS | `libacl1` | `2.3.1-3` | HIGH | `CVE-2026-54369` | `none reported` | fix_deferred |
| Debian OS | `libblkid1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-53613` | `none reported` | affected |
| Debian OS | `libblkid1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-76642` | `none reported` | affected |
| Debian OS | `libblkid1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78408` | `none reported` | affected |
| Debian OS | `libblkid1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78409` | `none reported` | affected |
| Debian OS | `libblkid1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78410` | `none reported` | affected |
| Debian OS | `libmount1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-53613` | `none reported` | affected |
| Debian OS | `libmount1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-76642` | `none reported` | affected |
| Debian OS | `libmount1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78408` | `none reported` | affected |
| Debian OS | `libmount1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78409` | `none reported` | affected |
| Debian OS | `libmount1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78410` | `none reported` | affected |
| Debian OS | `libsmartcols1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-53613` | `none reported` | affected |
| Debian OS | `libsmartcols1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-76642` | `none reported` | affected |
| Debian OS | `libsmartcols1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78408` | `none reported` | affected |
| Debian OS | `libsmartcols1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78409` | `none reported` | affected |
| Debian OS | `libsmartcols1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78410` | `none reported` | affected |
| Debian OS | `libsystemd0` | `252.39-1~deb12u2` | HIGH | `CVE-2026-16742` | `none reported` | fix_deferred |
| Debian OS | `libtinfo6` | `6.4-4` | HIGH | `CVE-2025-69720` | `none reported` | affected |
| Debian OS | `libudev1` | `252.39-1~deb12u2` | HIGH | `CVE-2026-16742` | `none reported` | fix_deferred |
| Debian OS | `libuuid1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-53613` | `none reported` | affected |
| Debian OS | `libuuid1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-76642` | `none reported` | affected |
| Debian OS | `libuuid1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78408` | `none reported` | affected |
| Debian OS | `libuuid1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78409` | `none reported` | affected |
| Debian OS | `libuuid1` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78410` | `none reported` | affected |
| Debian OS | `mount` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-53613` | `none reported` | affected |
| Debian OS | `mount` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-76642` | `none reported` | affected |
| Debian OS | `mount` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78408` | `none reported` | affected |
| Debian OS | `mount` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78409` | `none reported` | affected |
| Debian OS | `mount` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78410` | `none reported` | affected |
| Debian OS | `ncurses-base` | `6.4-4` | HIGH | `CVE-2025-69720` | `none reported` | affected |
| Debian OS | `ncurses-bin` | `6.4-4` | HIGH | `CVE-2025-69720` | `none reported` | affected |
| Debian OS | `perl-base` | `5.36.0-7+deb12u3` | CRITICAL | `CVE-2026-13221` | `none reported` | affected |
| Debian OS | `perl-base` | `5.36.0-7+deb12u3` | CRITICAL | `CVE-2026-42496` | `none reported` | fix_deferred |
| Debian OS | `perl-base` | `5.36.0-7+deb12u3` | CRITICAL | `CVE-2026-8376` | `none reported` | affected |
| Debian OS | `perl-base` | `5.36.0-7+deb12u3` | HIGH | `CVE-2026-42497` | `none reported` | fix_deferred |
| Debian OS | `perl-base` | `5.36.0-7+deb12u3` | HIGH | `CVE-2026-48962` | `none reported` | affected |
| Debian OS | `perl-base` | `5.36.0-7+deb12u3` | HIGH | `CVE-2026-57432` | `none reported` | affected |
| Debian OS | `perl-base` | `5.36.0-7+deb12u3` | HIGH | `CVE-2026-57433` | `none reported` | affected |
| Debian OS | `perl-base` | `5.36.0-7+deb12u3` | HIGH | `CVE-2026-9538` | `none reported` | fix_deferred |
| Debian OS | `util-linux` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-53613` | `none reported` | affected |
| Debian OS | `util-linux` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-76642` | `none reported` | affected |
| Debian OS | `util-linux` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78408` | `none reported` | affected |
| Debian OS | `util-linux` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78409` | `none reported` | affected |
| Debian OS | `util-linux` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78410` | `none reported` | affected |
| Debian OS | `util-linux-extra` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-53613` | `none reported` | affected |
| Debian OS | `util-linux-extra` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-76642` | `none reported` | affected |
| Debian OS | `util-linux-extra` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78408` | `none reported` | affected |
| Debian OS | `util-linux-extra` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78409` | `none reported` | affected |
| Debian OS | `util-linux-extra` | `2.38.1-5+deb12u3` | HIGH | `CVE-2026-78410` | `none reported` | affected |
| Debian OS | `zlib1g` | `1:1.2.13.dfsg-1` | CRITICAL | `CVE-2023-45853` | `none reported` | will_not_fix |

## Candidate residual findings

The candidate builder has 3 CRITICAL and 60 HIGH records. No HIGH/CRITICAL application JavaScript dependency was reported. The esbuild Go binary is an application build dependency and is executed during Vite build. Every candidate record has a Trivy fixed version, but many fixes require a separately scoped package-manager or toolchain update.

| Origin | Package | Installed | Severity | CVE/GHSA | Trivy fixed version | Status |
| --- | --- | --- | --- | --- | --- | --- |
| Corepack pnpm 9.15.0 | `brace-expansion` | `2.0.1` | HIGH | `CVE-2026-13149` | `5.0.7, 1.1.16, 2.1.2` | fixed |
| Corepack pnpm 9.15.0 | `brace-expansion` | `2.0.1` | HIGH | `CVE-2026-14257` | `5.0.8, 3.0.3, 2.1.3, 1.1.17` | fixed |
| Corepack pnpm 9.15.0 | `brace-expansion` | `2.0.1` | HIGH | `CVE-2026-69152` | `1.1.18, 2.1.4, 3.0.6, 5.0.9` | fixed |
| Corepack pnpm 9.15.0 | `cross-spawn` | `7.0.3` | HIGH | `CVE-2024-21538` | `7.0.5, 6.0.6` | fixed |
| Corepack pnpm 9.15.0 | `glob` | `10.4.5` | HIGH | `CVE-2025-64756` | `11.1.0, 10.5.0` | fixed |
| Corepack pnpm 9.15.0 | `ip-address` | `9.0.5` | HIGH | `CVE-2026-69192` | `10.3.1` | fixed |
| Corepack pnpm 9.15.0 | `minimatch` | `9.0.5` | HIGH | `CVE-2026-26996` | `10.2.1, 9.0.6, 8.0.5, 7.4.7, 6.2.1, 5.1.7, 4.2.4, 3.1.3` | fixed |
| Corepack pnpm 9.15.0 | `minimatch` | `9.0.5` | HIGH | `CVE-2026-27903` | `10.2.3, 9.0.7, 8.0.6, 7.4.8, 6.2.2, 5.1.8, 4.2.5, 3.1.3` | fixed |
| Corepack pnpm 9.15.0 | `minimatch` | `9.0.5` | HIGH | `CVE-2026-27904` | `10.2.3, 9.0.7, 8.0.6, 7.4.8, 6.2.2, 5.1.8, 4.2.5, 3.1.4` | fixed |
| Corepack pnpm 9.15.0 | `pnpm` | `9.15.0` | HIGH | `CVE-2025-69262` | `10.27.0` | fixed |
| Corepack pnpm 9.15.0 | `pnpm` | `9.15.0` | HIGH | `CVE-2025-69263` | `10.26.0` | fixed |
| Corepack pnpm 9.15.0 | `pnpm` | `9.15.0` | HIGH | `CVE-2026-50015` | `10.34.0, 11.4.0` | fixed |
| Corepack pnpm 9.15.0 | `pnpm` | `9.15.0` | HIGH | `CVE-2026-50016` | `10.34.0, 11.4.0` | fixed |
| Corepack pnpm 9.15.0 | `pnpm` | `9.15.0` | HIGH | `CVE-2026-55487` | `10.34.2, 11.5.3` | fixed |
| Corepack pnpm 9.15.0 | `pnpm` | `9.15.0` | HIGH | `CVE-2026-55697` | `10.34.2, 11.5.3` | fixed |
| Corepack pnpm 9.15.0 | `pnpm` | `9.15.0` | HIGH | `CVE-2026-55698` | `10.34.2, 11.5.3` | fixed |
| Corepack pnpm 9.15.0 | `pnpm` | `9.15.0` | HIGH | `CVE-2026-82392` | `10.34.5, 11.11.0` | fixed |
| Corepack pnpm 9.15.0 | `pnpm` | `9.15.0` | HIGH | `CVE-2026-82393` | `10.34.5, 11.11.0` | fixed |
| Corepack pnpm 9.15.0 | `pnpm` | `9.15.0` | HIGH | `GHSA-72r4-9c5j-mj57` | `10.34.4, 11.7.0` | fixed |
| Corepack pnpm 9.15.0 | `pnpm` | `9.15.0` | HIGH | `GHSA-fr4h-3cph-29xv` | `10.34.4, 11.7.0` | fixed |
| Corepack pnpm 9.15.0 | `pnpm` | `9.15.0` | HIGH | `GHSA-qrv3-253h-g69c` | `10.34.4, 11.8.0` | fixed |
| Corepack pnpm 9.15.0 | `tar` | `6.2.1` | CRITICAL | `CVE-2026-59873` | `7.5.19` | fixed |
| Corepack pnpm 9.15.0 | `tar` | `6.2.1` | HIGH | `CVE-2026-23745` | `7.5.3` | fixed |
| Corepack pnpm 9.15.0 | `tar` | `6.2.1` | HIGH | `CVE-2026-23950` | `7.5.4` | fixed |
| Corepack pnpm 9.15.0 | `tar` | `6.2.1` | HIGH | `CVE-2026-24842` | `7.5.7` | fixed |
| Corepack pnpm 9.15.0 | `tar` | `6.2.1` | HIGH | `CVE-2026-26960` | `7.5.8` | fixed |
| Corepack pnpm 9.15.0 | `tar` | `6.2.1` | HIGH | `CVE-2026-29786` | `7.5.10` | fixed |
| Corepack pnpm 9.15.0 | `tar` | `6.2.1` | HIGH | `CVE-2026-31802` | `7.5.11` | fixed |
| Corepack pnpm 9.15.0 | `tar` | `6.2.1` | HIGH | `CVE-2026-59874` | `7.5.18` | fixed |
| Corepack pnpm 9.15.0 | `tar` | `6.2.1` | HIGH | `CVE-2026-73566` | `7.5.21` | fixed |
| bundled npm 10.9.8 | `brace-expansion` | `2.0.2` | HIGH | `CVE-2026-13149` | `5.0.7, 1.1.16, 2.1.2` | fixed |
| bundled npm 10.9.8 | `brace-expansion` | `2.0.2` | HIGH | `CVE-2026-14257` | `5.0.8, 3.0.3, 2.1.3, 1.1.17` | fixed |
| bundled npm 10.9.8 | `brace-expansion` | `2.0.2` | HIGH | `CVE-2026-69152` | `1.1.18, 2.1.4, 3.0.6, 5.0.9` | fixed |
| bundled npm 10.9.8 | `ip-address` | `10.1.0` | HIGH | `CVE-2026-69192` | `10.3.1` | fixed |
| bundled npm 10.9.8 | `pacote` | `19.0.2` | HIGH | `CVE-2026-9496` | `21.5.1` | fixed |
| bundled npm 10.9.8 | `pacote` | `20.0.1` | HIGH | `CVE-2026-9496` | `21.5.1` | fixed |
| bundled npm 10.9.8 | `picomatch` | `4.0.3` | HIGH | `CVE-2026-33671` | `4.0.4, 3.0.2, 2.3.2` | fixed |
| bundled npm 10.9.8 | `sigstore` | `3.1.0` | HIGH | `CVE-2026-48815` | `4.1.1` | fixed |
| bundled npm 10.9.8 | `tar` | `7.5.11` | CRITICAL | `CVE-2026-59873` | `7.5.19` | fixed |
| bundled npm 10.9.8 | `tar` | `7.5.11` | HIGH | `CVE-2026-59874` | `7.5.18` | fixed |
| bundled npm 10.9.8 | `tar` | `7.5.11` | HIGH | `CVE-2026-73566` | `7.5.21` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | CRITICAL | `CVE-2025-68121` | `1.24.13, 1.25.7, 1.26.0-rc.3` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2025-61726` | `1.24.12, 1.25.6` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2025-61729` | `1.24.11, 1.25.5` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-25679` | `1.25.8, 1.26.1` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-27145` | `1.25.11, 1.26.4` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-32280` | `1.25.9, 1.26.2` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-32281` | `1.25.9, 1.26.2` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-32283` | `1.25.9, 1.26.2` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-33811` | `1.25.10, 1.26.3` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-33814` | `1.25.10, 1.26.3` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-33818` | `1.25.13, 1.26.6, 1.27.0-rc.3` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-39820` | `1.25.10, 1.26.3` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-39821` | `1.25.13, 1.26.6, 1.27.0-rc.3` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-39822` | `1.25.12, 1.26.5, 1.27.0-rc.2` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-39836` | `1.25.10, 1.26.3` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-42499` | `1.25.10, 1.26.3` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-42504` | `1.25.11, 1.26.4` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-56853` | `1.25.13, 1.26.6, 1.27.0-rc.3` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-56858` | `1.25.13, 1.26.6, 1.27.0-rc.3` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-56859` | `1.25.13, 1.26.6, 1.27.0-rc.3` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-56860` | `1.25.13, 1.26.6, 1.27.0-rc.3` | fixed |
| esbuild 0.25.9 Go binary | `stdlib` | `v1.23.12` | HIGH | `CVE-2026-56862` | `1.25.13, 1.26.6, 1.27.0-rc.3` | fixed |
