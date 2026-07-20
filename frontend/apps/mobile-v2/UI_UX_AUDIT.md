# Parkio mobile-v2 — Android UI/UX audit (2026-07-19)

Full page-by-page audit performed on a Pixel 7 AVD (Android 15, API 35, 1080×2400)
in **Expo Go SDK 56** against `scripts/mock-gateway.mjs`. Every screen was
exercised in **light + dark**, with **gesture nav and 3-button nav**, including
keyboard states and the full share/moderation flows. Screenshots live in the
session scratchpad (`shots/*.png`).

Android is edge-to-edge always-on in SDK 54+ — every screen must pad for the
status bar **and** the (transparent) gesture/3-button navigation bar itself.

---

## 1. Fixes applied in this audit

### Safe area (the reported Android overflow)
Top edges were mostly covered; the real gap was the **bottom** edge on stack
screens (no tab bar): fixed scroll padding (20–32px) sat under the 48dp
3-button bar and flush against the gesture bar.

| Screen | Fix |
| --- | --- |
| `impact`, `notifications`, `reports`, `smart-return`, `moderation/index`, `moderation/[id]`, `moderation/analytics`, `profile/{edit,vehicle,preferences,change-password,about}` | scroll/list `paddingBottom: insets.bottom + N` (matches the existing idiom in `map`/`spots/[id]`) |
| `spots/[id]` | scroll bottom padding now `insets.bottom + (132|40)` |
| `share/camera` | chrome `edges={['top','left','right']}`; dark bottom bar now extends under the nav bar with `paddingBottom: insets.bottom + 18` |
| `+not-found` | wrapped in `SafeAreaView` |
| `Sheet`, `ConfirmModal`, `MorningPromptModal`, smart-return `HomePickerModal` | `statusBarTranslucent` + `navigationBarTranslucent` so scrims cover the full screen on Android |

Already correct and verified on-device: tab screens (`AppTabBar` owns the bottom,
`Math.max(insets.bottom, 8)`), map overlays (`insets.top + 8`), spot-detail hero
chrome + sticky action bar, share wizard (full-edge SafeAreaView), onboarding
and auth screens, Toast host, OfflineBanner.

### Crashes / broken behavior
- **expo-notifications RedBox in Expo Go (Android)**: the module was removed
  from Expo Go in SDK 53; a `try/catch` around `require()` is not enough because
  Metro reports module-factory throws to LogBox as uncaught. The require is now
  skipped entirely when `Constants.executionEnvironment === StoreClient` on
  Android, and the permissions screen uses a new `requestPushPermissions()`
  from the service instead of its own inline require. Dev builds/EAS behave
  exactly as before.
- **api-client**: transport-level failures (request never sent) were normalized
  into an opaque "An unexpected error occurred." — now logged in dev
  (`[api] transport error: <code> <message>`) before normalization.

### Visual bugs (found on-screen, fixed)
- **PulseMotif core dot bleeding through hero text** on Welcome ("Parkio"
  wordmark) and Language screens; also the floating white dot on the Success
  celebration banner. `PulseMotif` gained a `core?: boolean` prop; backdrop
  usages pass `core={false}`.
- **`Button block={false}` self-aligns `flex-start`**, silently defeating parent
  centering/right-alignment. Fixed at every affected call site:
  - onboarding slides "Atla" now right-aligned (row + `justifyContent`),
  - `EmptyState` CTA centered (was left),
  - `MapCards` (permission / view-limit / empty) CTAs centered,
  - notifications "Tümünü okundu işaretle" now right-aligned.
- **SpotSheet**: at peek height a sliver of the expanded photo card poked above
  the fold — expanded block `paddingTop` 4→16.

All fixes verified live via Fast Refresh on the emulator; `tsc`, `eslint`,
`jest` (29) and api-client `vitest` (37) all pass.

---

## 2. Page-by-page results

### Onboarding
- **Language**: TR preselected, cards clear of system bars. ✔
  *Note:* tapping a row selects **and** navigates immediately — no explicit
  continue. Acceptable fast path; revisit if users mis-tap.
- **Slides ×3**: pulse/radius visuals, dots animate, copy wraps cleanly. ✔
  *Note:* last slide CTA still "Devam" — consider "Başla" on slide 3.
- **Permissions ×3**: priming card → system dialog order is right; card
  transition animation works; notification card fails soft in Expo Go. ✔
- **Welcome**: hero + beta chip + two CTAs, safe bottom. ✔ (dot fix above)

### Auth
- **Login**: keyboard resize verified — CTA and fields stay visible, focus ring
  correct, error/trace-id plumbing in place. ✔
- **Register**: checklist updates live, consent error state, footer links. ✔
- **Forgot / Check-email / Verify / Reset**: sent state, 60s resend cooldown,
  token fallback entry, success states — all render correctly. ✔
  *Note:* password eye icon tap target is ~20px; add `hitSlop`.

### Map (the core loop)
- Search pill + typeahead (mock geocoder) + recents, radius/level chip,
  "Bu bölgede ara" appears only after real pan-away, locate FAB, attribution. ✔
- Markers: freshness-ring pill with live in-WebView countdown; selected halo;
  suspicious marker shows a warning glyph. ✔
- SpotSheet peek → expand: evidence-first layout, actions pinned, countdown
  ticks, ring color transitions blue→amber→red verified live. ✔
- Spot detail: inset-aware glass chrome, countdown block + "her doğrulama
  süreyi uzatır", chips, community-signal timeline, mini-map, sticky action
  bar above both nav modes. ✔
- **Verify flow**: option sheet (danger tone on Riskli) → toast → countdown
  extended (01:46→20:09) → timeline gains "Doğrulandı" → +5 in impact ledger →
  notification created. Full loop ✔
- **Claim**: honest confirm copy (+30/+10, "rezervasyon değildir"). ✔
- **Report flow**: reason sheet (8 reasons, danger tones) → optional note
  (counter) → destructive submit → moderation case created. ✔

**Map findings (open):**
- **M1 — dead state without a GPS fix**: with permission granted but no
  position (slow/absent fix), nothing searches and the locate FAB gives no
  feedback. Place search is the only recovery. Recommend: timeout feedback on
  locate + auto-search of the default area when no fix arrives.
- **M2** — suspicious marker renders "⚠ −" (em-dash, no remaining time);
  confirm intended for SUSPICIOUS-with-time.
- **M3** — Android glass (no blur, alpha fill only): search results panel and
  detail action bar are readable but busy over map labels; consider a higher
  fill alpha on Android.

### Tabs
- **My Spots**: filters, pull-to-refresh, owner footers (doğrulama count,
  güven puanı, appeal on rejected), empty state (CTA now centered). ✔
- **Leaderboard**: podium (gold raised center, medal colors), anonymized
  handles, pinned "Sen" row, period chip. ✔
- **Profile**: identity hero, trust ring + level/points tiles, all menu rows
  navigate, staff section appears only for MODERATOR/ADMIN, danger sign-out,
  language/appearance sheets, sign-out-all confirm. ✔

### Share wizard (money flow — exercised end-to-end)
Camera (chrome, framing guide, hint chip, torch, gallery, retake/use) →
photo step (upload status card: uploading/scanning/ready/failed states seen) →
location step (GPS gate honest "aranıyor", center-pin adjust flips to
"Pin elle düzeltildi" and enables continue, address search wired) →
details (location summary + edit-back, counter, vehicle chips, context select,
legal segmented with Riskli hard-block card, advisory flags) →
review (preview SpotCard, honest ~10 dk lifetime note) →
publish → celebration (+5 puan, pending-validation card) → My Spots shows the
new spot live with countdown. **Draft resume after app restart verified.** ✔

**Share findings (open):**
- **S1 — upload transport flake in Expo Go**: the first two uploads failed
  *without reaching the gateway* (axios error, no response; gateway log empty),
  then succeeded with the identical file after a reload. Failure UI + retry +
  idempotency behaved correctly. Keep the new dev transport log; if it recurs
  on real devices, consider `FileSystem.uploadAsync` as the RN transport.
- **S2** — mock photos are 1-px black JPEGs, so every photo surface renders
  black in dev (mock-only; layout verified regardless).

### Sub-pages
- **Katkıların (impact)**: level hero + progress, radius diagram (solid current
  vs dashed next), stat tiles, live points ledger (+5 entry appeared after the
  verify), full level roadmap with current highlight. ✔
- **Bildirimler**: unread accent bars, filters, mark-all-read (now
  right-aligned), relative timestamps, tap-through routes. ✔
- **Bildirimlerim & itirazlar**: segmented tabs, sent-report cards with case
  ids, penalties empty state. ✔
  *Finding R1*: with zero penalties there is **no** manual appeal entry point
  (the "İtiraz oluştur" button only renders under existing penalty cards).
  *Finding R2 (copy)*: "Bildirimler" vs "Bildirimlerim" near-collision in TR;
  consider "Şikayetlerim & itirazlar".
- **Akıllı Dönüş**: master toggle guard (asks for home first), home picker
  modal (map + pin + label + locate), 15-min stepper, lead chips, privacy
  card. ✔
  *Finding SR1*: with mock defaults no lead chip reads as selected — verify
  default `reminderLeadMinutes` mapping; selected-chip contrast is also subtle
  in dark.
- **Profile sub-pages** (edit / vehicle / preferences / change-password /
  about): verified badge row, İzmir plate placeholder, level-gated radius chips
  with explanatory copy, password checklist gating save, about privacy note. ✔
- **Moderation** (as `mod@parkio.dev`): queue (severity dot, meta chips,
  status filters, İtirazlar tab), case detail (facts, evidence with target id,
  assign "Üstlen" → İncelemede, role-gated action sheet incl. admin actions
  with danger tones, resolve → toast → Kapatılan + terminal action chip),
  platform analytics (KPI tiles + funnel). Full loop ✔
  *Finding MOD1 (copy)*: the resolve ConfirmModal body reuses the note
  placeholder ("Kararın gerekçesi...") — needs a real confirmation sentence.

### System-level
- **Dark mode**: calm-navy surfaces everywhere, status bar icons flip, the map
  dark CSS filter produces a genuinely readable dark basemap, dark marker
  pills/sheets/modals all correct. ✔
- **3-button nav**: tab bar, stack scroll bottoms, wizard footer and detail
  action bar all clear the 48dp bar after the fixes. ✔
- **Splash**: renders icon + name correctly on activity restart. ✔
- **Toasts**: safe-area aware, stack, auto-dismiss. ✔
- **not-found**: now safe-area wrapped. ✔
- **Suspended wall**: not reachable with mock seeds (no suspended account);
  code-reviewed only — layout is a centered column + full SafeAreaView, no
  overflow risk.

### Environment caveats (not app bugs)
- Expo Go's dev gear bubble overlays the top-right corner in most screenshots.
- `adb emu geo fix` never reached the emulator's location providers, which is
  what surfaced finding M1; on real hardware a fix would normally arrive.
- Status bar shows 12-h time while the app formats 24-h (`formatClock`) — same
  moment, device format setting.

---

## 3. Recommended follow-ups (priority order)

1. **M1** map no-fix dead state — feedback on locate + default-area fallback.
2. **R1** appeals entry with zero penalties.
3. **S1** keep an eye on the RN multipart transport on real devices.
4. **MOD1 / R2** copy fixes (confirm body, "Şikayetlerim").
5. **M3** Android glass alpha bump for search panel / action bar.
6. **SR1** smart-return default lead selection + chip selected contrast.
7. Small a11y: eye-toggle `hitSlop`, trailing-icon support in `Button`
   (for "Topluluk sinyali ›").
