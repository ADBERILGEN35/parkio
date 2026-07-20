# Parkio design build — progress & Pencil ID map

File: `untitled.pen` (project root). Brief: `PARKIO-DESIGN-BRIEF.md`.
Theme axis `mode: light | dark` is registered on all core color variables (dark = mobile only).

## Done (2026-07-18, session 1)

- **Variables**: full §4 token set incl. dark values, `glass`, `ring-track`, fonts (`font-body` Inter, `font-display` Space Grotesk, `font-mono` JetBrains Mono), radius numbers.
- **00 Foundations** (`xKliq` at 0,0): color swatches + type ramp.
- **01 Components** (`JeyEU` at 1063,0): all 20 component groups from §7 + brand logo + admin side nav + mobile status/tab bars.
- **§9 Web App screens**:
  - Row y=3699: `web/map — default` (`hwyZL`) + empty (`Z9jCjL`) · loading (`jxS6v`) · view-limit (`PZmMc`) · smart-return (`J4D5UW`) — x steps 1520
  - Row y=4759: `web/spot-detail — verified` (`w6xrX`) + pending-validation owner (`n897r2`) · filled (`yp4vL`) · rejected owner (`vzpTa`)
  - Row y=5919: upload steps 1/1-ready/2/3/3-riskli/4/success/guard (`cLrxy`,`gB5N8`,`rXHxS`,`ABBYK`,`KuUVZ`,`IqDKK`,`pUV7r`,`bXbLV`)
  - Row y=8139: my-spots (`Z6nMNF`) · leaderboard (`pRc8o`) · notifications (`ewaea`) · gamification (`PYXXf`) · reports-sent (`DDS5V`) · reports-penalties (`RQPbW`) · appeal modal (`aI7Fm`) · profile (`M2jB6` — tall 1660)
- **§8 Web Auth** row y=6979: login (`f2o7bZ`) · login-error (`VnGqt`) · register (`lqfmJ`) · check-email (`TUWaR`) · verify-success (`Q6td40`) · forgot (`ss0EF`) · forgot-sent (`kfJSc`) · reset (`e1qrB7`) · reset-expired (`OHeAJ`) · legal/privacy (`D9ZBhl`)
- **§10 Admin** row y=9959: dashboard (`cBy3r`) · moderation case-open (`jnMnC`) · appeal-review (`lFuse`) · users (`KM1AP`) · security (`mIHqY`) · analytics (`g1YcZ1`) · audit (`J5Dgt8`) · system (`PW5H7`) · user-detail (`HEgZy`)
- **§11 Landing**: `landing/desktop` (`SRYtD` at 0,11119) — hero w/ 3-ring map fragment, problem, loop, how-it-works, features, navy privacy band + tech strip, beta expectations, FAQ, waitlist, footer
- **§12 Mobile light** (row below landing, `mobRowY`≈find via layout): map default (`V2Kw7l`) · sheet-peek (`VrgtM`) · sheet-expanded (`xHdwd`) · permission-card (`x6vdz`) · spot-detail verified (`Z7mqs4`) · share source-sheet (`bGJ1T`) · camera (`uXZnx`) · share details (`B2qzu`) · share success (`RL6Sn`) · impact (`tJsqx`) · onboarding radius slide (`jIb4v`) · smart-return morning prompt (`zQkiP`)
- **§12 Mobile dark** (row below): map (`IB3hr`) · spot-sheet expanded (`u0jsT`) · spot-detail (`JJwE2`) — via `theme:{mode:"dark"}` + overrides (see Dark reskin recipe below)

## TODO (next sessions)

- §11 `landing/mobile` 390
- §9 mobile-web 390 variants (map bottom-sheet collapsed/expanded, spot detail, upload, notifications)
- §12 remaining mobile light: onboarding language + value slides 1–2 + permission priming (konum/bildirim/kamera) + auth landing; mobile auth (login/register/forgot/check/verify); spot detail pending+filled variants; share steps preview/prep, upload progress (retry/offline), GPS gate, location adjust, review, draft-resume; my-spots/leaderboard/notifications/reports mobile; smart-return settings + today card + result notification; profile hub + subscreens (Araç, Bildirim tercihleri, Şifre, Hakkında, Oturumlar); staff moderation (queue/case/analytics)
- §12 remaining dark reskins: share camera flow already dark; still need dark: upload success, impact, notifications, profile
- Optional polish: spot-detail Suspicious + Expired + PENDING_REVIEW variants (web has pending/filled/rejected), verify-email verifying/expired variants, notifications empty state frame

## Dark reskin recipe (worked)

Copy light frame with `theme:{mode:"dark"}` + `fill:"#0B1626"`, then override hardcoded values:
streets `#FFFFFF12`, blocks `#16273F`, park `#0F2A1F`, glass strokes `#FFFFFF`→`#FFFFFF14`, pulse strokes → `#B3C5FF29/3D/52`, verified accents `#006C49`→`#6CF8BB` on `#0F2A1F`, amber `#A06500`→`#FFB955`, tonal/ghost button labels → `#B3C5FF`, avatar circles `#1D3049` + `#B3C5FF`. Tokens (`$surface`, `$on-surface`, `$glass`, `$ring-track`, `$primary`…) adapt automatically.

## Reusable component IDs (ref these, never rebuild)

| Component | id | Key child ids (override via descendants) |
|---|---|---|
| Button / Primary | `JTvDY` | label `jAG56` |
| Button / Tonal | `CDZZ4` | label `kzJSW` |
| Button / Ghost | `a9xiMD` | label `F1Hvv` |
| Button / Destructive | `pVRb7` | label `xGEsY` |
| Button / Icon | `n9PWRX` | glyph `Gbhms` |
| Input / Text | `YlULt` | label `ltQSW`, field `fGq31`, value `eTvAk`, trailing icon `Wfnld` (disabled by default), helper `MPudp` |
| Input / Textarea | `F3BcZ0` | value `m4x50`, counter `txZD0` |
| Input / Select | `Rc9II` | label `Iez0M`, field `nJ7Sc`, value `xuOop` |
| Checkbox | `IRk09` | box `r15lnr`, check `A2m01k`, label `CJPSh` (off = box fill surface + stroke outline, check disabled) |
| Radio | `ThlV0` | circle `LMFgq`, dot `B4MjA1`, label `sVVnK` |
| Toggle | `L8Ipk` | off = `fill:#C2C6D8, justifyContent:start` |
| Badge / Status | `fHOas` | icon `qYG8R`, text `WjYQE`; tint via root `fill` |
| Chip / Attribute | `Z6qWY` | icon `K3EVcB`, text `KgvKf` |
| Chip / Trust Band | `IYMyH` | icon `kgcvF`, text `qfCm3` |
| Chip / Level | `x9mWG` | text `ngF5Q` |
| Freshness Ring 28/48/64 | `gE2KP` / `ZJgfu` / `LEuGt` | arcs `VMsIb` / `Wwu0k` / `YFs93` (override fill+sweepAngle) |
| Pulse Motif | `C83272` | — |
| Marker / Spot | `L8duX` | pill `E5VDZv`, icon `LLhOm`, countdown `NscI2` |
| Marker / Pin (teardrop) | `sqphF` | — |
| Card / Spot | `zkmb2` | photo `VXukl`, trust icon `HsSf8`, trust text `pdcnZ`, ring ref `yrMfd` (arc path `yrMfd/VMsIb`), countdown `NNGSy`, title `lx8mm`, fresh `Mh2lv`, chips veh `r2q9A` ctx `d1jgM` legal `fndMy` (chip text path e.g. `r2q9A/KgvKf`), avatar text `lyGu0`, name `jhs6W`, band `z80j4`, distance `MnNTz` |
| Search Pill / Glass | `Pa3rs` | placeholder `r3uHB` |
| Trust Ring | `aLmDq` | arc `bhDKf`, score `cBAuZ` |
| Level Progress | `Bmuip` | level `I4zURU`, points `LyZJp`, bar `i7tOu5` |
| Radius Diagram | `dWb8q` | current chip text `H6yLV`, next chip text `tNGdD` |
| Verification Timeline | `tOaxC` | rows: titles `dWiJr`/`c5zee`/`vrPZx`, whos `yhcgI`/`g8kFu`/`ihSTf`, rows 2/3 `DPrdn`/`TydhF` |
| Card / Notification | `PBNDh` | accent `XwooY`, icon wrap `JLyRW`, icon `nptTs`, title `uN7Ic`, time `apFkM` |
| Toast | `e5ICw` | text `JmoPj`, action `V3AudB` |
| Wizard Progress | `PDGmS` | bars `AUTRL`,`ei0GJ`,`CZMzZ`,`v41Fk`; labels `QQenk`,`ICFDP`,`iuHSP`,`pwlLd` (default state = step 2 active) |
| Bottom Sheet | `g4BXl` | peek title `Mzirw`, meta `e1GWmA`, count `hzc2x`, thumb `k1UUc` |
| Modal / Confirm | `zNAuk` | title `yO4zT`, body `KQjZ1`, cancel `I3XA1x`, confirm `yHkKy` (labels via `I3XA1x/F1Hvv`, `yHkKy/jAG56`) |
| Empty State | `sVBba` | title `H9JbiE`, CTA `TjyFK` |
| Skeleton / Spot Card | `XfLth` | — |
| Card / View Limit | `EJKrO` | — |
| Admin Table | `Y9kobP` | — |
| Leaderboard Podium | `ViVe1` | — |
| Row / Leaderboard | `z2HY0` | rank `nImbV`, name `BLBck`, band `jGZR3`, points `pdyQM` |
| Banner / Celebration | `vuiH1` | title `c9tcvf`, sub `LoTUg`, chip text `vEeo6` |
| Banner / Smart Return | `wEWpX` | text `Pom7C` |
| Logo / Mark | `k4Gzj4` | — |
| Logo / Lockup | `f0VU5V` | — |
| Web Top Nav (reusable) | `v6UVzj` | active item = Harita (text `A0d3W`, rail `uv4T4`); other items: Park yerlerim `PX4lL`/`GQQTO`, Katkıların `JpSKB`/`sIArA`, Liderlik `PDDAP`/`n8FeP`; share CTA `vHjfB` |
| Admin Side Nav | `uKg7Y` | active = Dashboard (`H3xfy1` item / `M3A4Z` icon / `j6onhd` text); Moderasyon `sjode`/`O4k5M`/`hIqzZ`, Kullanıcılar `p4Od70`/`n5rZg`/`a6dJW7`, Analitik `l1M4bE`/`Cf4rJ`/`k1mlt`, Güvenlik `RVXDs`/`euGI0`/`kkc5r`, Denetim `nsaew`/`cc5QQ`/`IaDfm`, Sistem `J9vQK2`/`RLflV`/`ulG11` |
| Mobile Status Bar | `Oeybf` | — |
| Mobile Tab Bar | `u8joEP` | active = Harita; raised Paylaş circle `e2ECwf` |
| Card / View Limit | `EJKrO` | — |

Stock photo URLs reused across frames (Unsplash): street `photo-1547463981-8edaded9702b`, seaside `photo-1759889392276-0d35d179ae9d`, lot `photo-1781341031275-f4bbee741c24` — full URLs in existing nodes (e.g. `mySpots` grid).

## Conventions locked in

- Status badge tints: blue `#0050CB1A`, emerald `#006C491A`, amber `#7F4F001A`, slate `#4246561A` (Doluldu uses `#E5EEFF`), red `#BA1A1A1A`.
- Ring life colors: fresh `$primary`, aging `#A06500`, expiring/`Son 2 dakika` `#BA1A1A`; indeterminate/pending = `#727687` sweep −90.
- Glass recipe: fill `$glass` + background_blur 20 + 1px `#FFFFFF` stroke.
- Basemap: base `#ECEFF3`, blocks `#E2E7EC`, parks `#DEE8DC`, water `#D9E4EE`, streets `#FFFFFFE6`.
- Ambient-soft shadow `0 4 20 #0000000D`; ambient-deep `0 12 40 #0000001A`; blue glow only on celebration + selected marker.
- Screen rows on canvas: map row y=3699, detail row y=4759, upload row y=5919; place new sections in fresh rows below (x steps of 1520).
- Attr chips: padding [6,10], icon 14 — max ~3 chips per 340 card; hide legal chip when labels run long.
