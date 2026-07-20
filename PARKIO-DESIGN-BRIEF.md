# PARKIO — MASTER DESIGN BRIEF FOR PENCIL

> **How to run this in Pencil:** Paste **§1–§7 first** in one prompt so the agent registers the mission, guardrails, tokens, and component system. Then generate screens **section by section** (§8 → §12) in separate prompt runs, always keeping §2 (Product Truths), §4 (Tokens), and §5 (Signature Language) in context. Name every frame using the convention in §7.6. If you drive Pencil from Claude Code, save this file in the project as `PARKIO-DESIGN-BRIEF.md` and reference it from every sticky-note prompt.

---

## 1. MISSION & DESIGN THESIS

You are designing the **complete UI** for **Parkio** — a community-powered parking intelligence platform. Drivers share a photo of an open parking spot; nearby drivers discover it, verify it, claim it after parking, or report it. Trust scores, functional levels, moderation, and advisory AI photo validation keep the signal honest.

**Design thesis — "The Living Signal."** Parkio's content is *perishable*. A spot lives ~10 minutes. It gets extended when verified, and it dies when someone parks in it. No other product has 10-minute-lifetime content, so **time is the design material**: countdowns, freshness decay, verification timestamps, and pulse/radar motifs are the visual core — not decoration. The product should feel like watching a city breathe.

**Mantra:** "Concierge for the curb." Calm, premium, quiet confidence. A reliable companion for uncertain parking moments — never a taxi app, never a game, never a generic SaaS dashboard.

**Quality bar:** This must NOT look like default AI output. No interchangeable card grids, no purple gradients, no template hero with two buttons and a floating dashboard screenshot. Every screen must be recognizably Parkio through the signature system in §5. Spend boldness in ONE place (the Freshness Ring + pulse system); keep everything around it disciplined and quiet.

---

## 2. HARD PRODUCT TRUTHS — NON-NEGOTIABLE GUARDRAILS

Violating any of these makes the design wrong, no matter how pretty. Re-check this list before finishing every screen.

1. **Money does not exist in this product.** No prices, no hourly rates, no "$"/"₺" anywhere, no "Book"/"Reserve" buttons, no payment forms, no revenue charts, no wallets. The three actions on a spot are free: **Verify · Claim ("Park ettim") · Report**. Where a parking app template would show a price, Parkio shows **status + freshness countdown + distance + vehicle fit + trust**.
2. **Spots are ephemeral signals, not listings.** Lifetime ≈ 10 min at creation; each "available" verification extends it (+15, then +20 min); two "filled" reports kill it (FILLED). Every representation of a spot (marker, card, detail) must show remaining life and last-verified time.
3. **One photo per spot. Always.** Never design galleries, carousels, or multi-photo uploads. Photo metadata (EXIF/GPS) is stripped server-side — this is a privacy feature worth surfacing in copy.
4. **Auth is email + password only.** Mandatory email verification before login. **No Google/Apple/Facebook/social login buttons anywhere.**
5. **Gamification scope is exactly:** points, levels L1–L5 with *functional* perks, trust score 0–100 with bands, and a leaderboard. **No streaks, no badges, no achievements, no contribution heatmaps.** Gamification is a support feature, never the brand core — no trophy iconography as identity.
6. **Levels are functional, not cosmetic.** Leveling up literally widens your search radius, result count, and daily spot views (L1: 300 m / 3 results / 20 views → L5: 2500 m / 25 results / 300 views). Make this visible: "Level up to see further."
7. **Legal status is advisory, never guaranteed.** Values: Legal-looking / Uncertain. (Illegal/risky submissions are rejected at creation and never appear publicly.) The app never claims a spot is legal or safe — copy must reflect that.
8. **Turkish is the default language; English secondary.** Design WITH Turkish strings (they run ~20–30% longer). Never bake text into images. Every status uses **icon + label**, never color alone.
9. **Honest beta.** No fake user counts, no testimonials, no "trusted by thousands," no "launched," no fake urgency. Allowed vocabulary: community-powered, parking intelligence, photo-backed, verified, trust signals, hosted-beta release candidate, privacy-conscious.
10. **Public marketing does NOT name the city.** Landing copy says "one focused pilot area" — only the in-app sample content uses İzmir.
11. **Status lifecycle (memorize):** PENDING_VALIDATION → (AI photo gate) → ACTIVE → VERIFIED / SUSPICIOUS → FILLED / EXPIRED / REJECTED, plus PENDING_REVIEW when AI flags a warning. Only ACTIVE and VERIFIED spots are publicly discoverable.
12. **Privacy is a visible feature.** Location choices, Smart Return home location, and photo-metadata stripping are explained in-context in plain language — never buried.

---

## 3. BRAND PERSONALITY & VOICE

- **Personality:** Calm > energetic · Practical > playful · Modern > corporate · Human > enterprise · Trustworthy > flashy. Privacy-conscious. Technically strong.
- **Should feel like:** a reliable companion for uncertain parking moments; a community tool with strong technical guardrails; a modern mobility product that doesn't copy map apps.
- **Should NOT feel like:** a taxi/ride-hailing brand, a crypto project, an arcade game, a dark cybersecurity product, a government portal, a generic SaaS dashboard.
- **Voice:** clear, honest, useful, human. Active voice. A button says exactly what happens ("Yer paylaş", not "Gönder"). Errors explain what went wrong and what to do; they never apologize vaguely. Empty states are invitations to act.
- **North star:** "Clear parking decisions through trusted community signals."
- **Logo:** a vivid blue rounded "P" with a white car silhouette tilted inside the bowl of the P. Lockups: horizontal, stacked, symbol-only, wordmark-only ("Parkio", sentence case, normal tracking). App icons use the symbol alone. In frames, use a placeholder rounded-P mark in `primary` blue — do not invent a different logo.

---

## 4. DESIGN TOKENS — REGISTER THESE AS VARIABLES FIRST

Material-3-style roles. **Light theme is the brand identity.** Blue is canonical (do not use teal — a legacy palette that is dead).

### 4.1 Color

| Token | Value | Use |
|---|---|---|
| `primary` | `#0050CB` | CTAs, active nav, links, focus rings, active markers, "Active" status |
| `primary-container` | `#0066FF` | Filled accents, gradient partner for celebration moments |
| `primary-fixed` | `#DAE1FF` | Soft blue fills, selected chips |
| `primary-fixed-dim` | `#B3C5FF` | Secondary blue fills, progress tracks |
| `on-primary` | `#FFFFFF` | Text/icons on primary |
| `secondary` (Verified Emerald) | `#006C49` | "Verified" badges, success, positive trends |
| `secondary-container` | `#6CF8BB` | Verified soft fills |
| `tertiary` (Amber) | `#7F4F00` | Warnings, "In review", caution accents |
| `tertiary-container` | `#A06500` | Amber fills |
| `error` | `#BA1A1A` | Urgent, destructive, reports, expiring |
| `error-container` | `#FFDAD6` | Error soft fills |
| `background` | `#F8F9FF` | Page (blue-tinted near-white) |
| `surface` | `#FFFFFF` | Cards |
| `surface-container-1…4` | `#EFF4FF → #E5EEFF → #DCE9FF → #D3E4FE` | Tonal ramp for hierarchy WITHOUT borders |
| `on-surface` | `#0B1C30` | Primary text (navy ink) |
| `on-surface-variant` | `#424656` | Secondary text |
| `outline` / `outline-variant` | `#727687` / `#C2C6D8` | Rare hairlines, input strokes |
| `inverse-surface` | `#213145` | Tooltips, inverse chips |

**Status semantics (icon + label required, never color alone):**
Active = blue `#0050CB` · Verified = emerald `#006C49` · In review / Warning = amber `#7F4F00` · Filled / inactive = slate (`#424656` on `#E5EEFF`) · Expired / urgent / rejected = red `#BA1A1A` · Pending validation = slate with hourglass icon · Suspicious = amber with alert icon.

### 4.2 Dark theme (MOBILE APP ONLY — web ships light-only)

Calm deep navy, never black, never neon, never "hacker."
`background #0B1626` · `surface-1 #101E33` · `surface-2 #16273F` · `surface-3 #1D3049` · text `#E7ECF7` · secondary text `#A7B0C4` · hairline `rgba(255,255,255,0.08)` · primary CTA stays `#0066FF` with white text · accents/links shift to `#B3C5FF` · verified `#6CF8BB` on `#0F2A1F` · amber `#FFB955` on `#2A1F0A` · error `#FFB4AB` on `#3A0E0C` · glass = `rgba(13,22,38,0.72)` + blur 20px + hairline.

### 4.3 Typography — Inter everywhere (400/500/600/700)

Space Grotesk permitted ONLY for landing-page hero display moments. **Countdowns and all numbers use tabular figures.**

| Style | Spec |
|---|---|
| display-lg | 48 / 1.1 / 700 / −0.02em |
| headline-lg | 32 / 1.2 / 700 (24 on mobile) |
| headline-md | 24 / 1.3 / 600 |
| title-lg | 20 / 1.4 / 600 |
| body-lg | 16 / 1.6 / 400 |
| body-md (default) | 14 / 1.5 / 400 |
| label-md | 12 / 600 / UPPERCASE, +0.06em tracking |
| label-sm | 11 / 500 |
| countdown-lg (custom) | 28 / 700 / tabular / −0.01em |

### 4.4 Shape, elevation, glass, motion

- **Radius:** 8px inputs & buttons · 12–16px cards · 24–32px premium cards, sheets, modals · full pill for badges, chips, markers, search bars, primary CTAs.
- **Shadows:** ambient-soft `0 4px 20px rgba(0,0,0,0.05)` on cards · ambient-deep `0 12px 40px rgba(0,0,0,0.10)` on modals/sheets · a soft blue glow reserved ONLY for celebration moments and the active map marker pulse.
- **Borders:** avoid hard structural borders. Hierarchy comes from the tonal surface ramp + ambient shadow. Hairlines (`outline-variant`) only for inputs and dividers inside dense lists/tables.
- **Glass (anything floating over the map):** `rgba(248,249,255,0.7)` + `backdrop-blur(20px)` + 1px hairline white border. Used for: top nav, floating search pill, map overlays, sticky action footers, bottom sheets.
- **Motion:** 100ms micro · 250ms standard · 400ms panels · spring `cubic-bezier(0.34,1.56,0.64,1)` · press = scale 0.95 · pulse-glow on the active marker · slide-in map sidebar · shimmer skeletons on `#F8F9FF` · respect reduced-motion.
- **Icons:** Material Symbols Outlined (variable). FILL 0 default, FILL 1 for active states. Themes: parking, pin, search, camera, check/verify, flag/report, shield/privacy, bell, clock, radar, groups/community. **No emoji as icons, ever.**

---

## 5. SIGNATURE VISUAL LANGUAGE — "THE LIVING SIGNAL" (ANTI-SLOP SYSTEM)

These seven devices make Parkio unmistakable. Use them consistently; do not invent competing motifs.

1. **The Freshness Ring (THE signature).** A thin 2–2.5px circular ring that depletes clockwise, wrapped around: map spot markers, the photo thumbnail on every spot card, and the timestamp chip on spot detail. Ring color tracks remaining life: >66% = `primary` blue · 33–66% = amber · <33% = red. Always paired with a tabular-numeral countdown ("07:42 kaldı") — the ring is never the only indicator. This single element carries the product's ephemerality everywhere.
2. **The Pulse motif.** Concentric radar rings emitting from a point — the graphic embodiment of "signal." Uses: pulse-glow on the selected map marker, empty states, the level-radius diagram, onboarding illustrations, and the celebration moment. Rendered in `primary` at 8–16% opacity steps. This replaces generic illustration packs entirely — Parkio needs no stock illustrations.
3. **Glass over map.** The map is the ground layer of the product; all chrome floats above it as glass (recipe in §4.4). The map screen should feel like a clean windshield HUD, not a webpage with a map widget.
4. **Tonal depth, no boxes.** Sections separate via the `#EFF4FF → #D3E4FE` surface ramp and ambient shadow — not via bordered boxes. When you're tempted to draw a border, use a tonal step instead.
5. **Evidence stack.** Every spot representation orders its content as proof: **photo → freshness → verifier count → contributor trust band**. Trust is shown, not claimed: "2 kişi doğruladı · 3 dk önce" beats any marketing adjective.
6. **Time typography.** Countdowns and "verified X min ago" lines are typographic heroes — large, tabular, confident. On spot detail, the countdown is co-primary with the photo.
7. **Level = sight.** Visualize levels as concentric radius circles on a mini-map ("300 m'den 2500 m'ye") — not just an XP bar. "Seviye atla, daha uzağı gör."

**BANNED (instant slop):** purple/violet/indigo gradients · emoji as icons · generic 3-column icon-title-blurb feature grids · testimonial carousels, fake logos, fake counters · trophy/confetti-heavy game visuals · dark neon/"hacker" aesthetics · price tags or currency symbols · stock photos of people shaking hands · drop shadows on everything · bento grids without informational purpose · the centered-hero-with-two-buttons-and-floating-dashboard-screenshot cliché · lorem ipsum (use §12 content).

---

## 6. LAYOUT SYSTEMS & APP SHELLS

- **Grid:** 4/8pt spacing system. Desktop content max-width 1200px (admin 1320px); map routes are full-bleed.
- **Frame sizes:** Web desktop **1440 × (900+, extend for scroll)** · Web mobile **390 wide** · Mobile app **393 × 852** · Landing desktop 1440 + mobile 390.
- **Web app shell (authenticated):** 64px **glass top nav** — logo left · center: Harita · Park yerlerim · Katkıların · Liderlik · right: primary pill **"Yer paylaş"** + bell with unread dot + avatar menu. Active nav item = 600 weight + `primary` + small underline rail. On mobile web, the top nav collapses and a **5-tab bottom bar** appears: Harita · Yerlerim · **Paylaş** (center, raised circular primary button) · Liderlik · Profil; active tab = pill highlight.
- **Map layout (web):** full-bleed map under the glass nav. Desktop: a 400px **left glass results panel** slides in over the map (list + filters), map controls bottom-right (locate + zoom stack). Mobile web: draggable bottom sheet (collapsed peek 96px → half → full) with drag handle.
- **Admin shell:** separate 256px light side-nav (Dashboard · Moderation · Users · Analytics · Security · Audit log · System), denser 13–14px type, same tokens — an admin console that is calm, never a "dark ops" theme.
- **Mobile app shell:** 5-tab bottom bar: Harita · Park yerlerim · **Paylaş** (raised center FAB-tab) · Liderlik · Profil. Light + dark themes.

### 6.1 Frame naming convention (use exactly)
`web/map — default` · `web/map — empty` · `web/spot-detail — verified` · `web/upload/step-2 — default` · `admin/moderation — case-open` · `mob/map — permission-card` · `mob-dark/spot-sheet — default` · `landing/desktop` · `landing/mobile`.
Organize Pencil pages: **00 Foundations · 01 Components · 02 Web Auth · 03 Web App · 04 Admin · 05 Landing · 06 Mobile Light · 07 Mobile Dark**.

---

## 7. COMPONENT SHEET — BUILD BEFORE ANY SCREEN (page "01 Components")

Lay these out on one sheet with labels; reuse everywhere.

1. **Buttons:** primary (pill, `primary` bg, white text), tonal (pill, `primary-fixed` bg, `primary` text), ghost, destructive (error), icon button. States: default / hover / pressed (scale .95) / disabled / loading.
2. **Inputs:** text field (8px radius, `outline-variant` stroke, focus = 2px `primary` ring), with label + helper + error state showing message and, when relevant, a small mono trace-id line (`trc_9f3a12…` + copy icon). Textarea with counter ("0 / 1000"). Select, checkbox, radio, toggle.
3. **Soft status badges (pill, bg = color/10%, text = color, leading icon):** Aktif (blue, check_circle) · Doğrulandı (emerald, verified) · İncelemede (amber, hourglass) · Doğrulama bekliyor (slate, hourglass_empty) · Şüpheli (amber, warning) · Doluldu (slate, block) · Süresi doldu (red, timer_off) · Reddedildi (red, cancel).
4. **Freshness Ring component** (see §5.1) in 3 sizes: marker 28px, card-thumb 48px, detail 64px — each with countdown text lockup.
5. **Spot map marker:** status-pill marker (pill with status icon + countdown, caret pointing down, Freshness Ring halo); selected state = pulse-glow rings. Plus teardrop drop-pin with bounce for location picking.
6. **Spot card:** photo header (16px radius) with glass trust badge overlay top-left and Freshness Ring + countdown top-right · title (street/area) · freshness line ("3 dk önce paylaşıldı · 2 doğrulama") · metadata chips (distance, vehicle fit, context, legal) · contributor footer (avatar, name, trust-band chip).
7. **Attribute chips:** pill, `surface-container-1` bg, icon + label (e.g., "directions_car Sedan", "signpost Cadde üstü", "gavel Belirsiz").
8. **Glass search pill** (map): search icon + placeholder "Nereye park edeceksin?" + geolocate button; expanded state shows typeahead results list.
9. **Trust ring:** SVG circular gauge 0–100 with band label (Güvenilmez <25 / Düşük / Orta / Yüksek ≥75) — band color: red / amber / blue / emerald.
10. **Level progress:** horizontal gradient bar (`#0050CB → #0066FF`) with "Seviye 3 · 240 / 400 puan"; plus the **radius diagram**: concentric circles on a faint mini-map showing current vs next level reach.
11. **Verification timeline:** vertical line with check-circle nodes: "Paylaşıldı 14:02 → Doğrulandı 14:05 (Elif A.) → Doğrulandı 14:11 (Baran T.)".
12. **Notification card:** left unread accent bar (`primary`), icon by type, title + body + relative time, subtle deep-link chevron.
13. **Bottom sheet** with 36×4px drag handle, glass or `surface` variant. **Modal** (24px radius, ambient-deep). **Toast/snackbar** (inverse-surface).
14. **Wizard progress:** 4 segment pills with labels (Fotoğraf · Konum · Detaylar · Önizleme), animated fill.
15. **Empty state:** pulse motif illustration + one-line invitation + primary action.
16. **Skeletons:** shimmer blocks on `#F8F9FF` matching card anatomy.
17. **Admin table:** 48px rows, sticky header, hairline row dividers, sortable headers, row hover `surface-container-1`, inline status badges, pagination.
18. **Leaderboard podium:** top-3 with medal icons and elevated tonal cards; list rows below.
19. **Celebration banner:** blue gradient (`#0050CB → #0066FF`) + pulse rings + points chip — the ONLY place the blue glow shadow appears.
20. **Smart Return banner** (map): glass strip with clock icon, "Akıllı Dönüş: 18:30'a ayarlı · Evinin yakınında arayacağız", dismiss + settings link.

---

## 8. SCREENS — WEB AUTH & LEGAL (page "02 Web Auth", 1440 frames + 390 variants for login/register)

All auth uses the **auth split layout**: left 45% photo pane — a blue-duotone urban curb photograph (no text baked in) with ONE floating glass spot-card (illustrative, with Freshness Ring) — right 55% form pane on `background`, logo top-left, language switch TR/EN top-right, form max-width 400px.

1. **/login** — "Tekrar hoş geldin." Email, password (show/hide), "Şifremi unuttum" link, primary "Giriş yap", footer "Hesabın yok mu? Kayıt ol". **No social buttons.** Error state variant: banner "E-posta veya şifre hatalı." + trace-id line.
2. **/register** — email + password + password rules helper (live checklist) + KVKK/terms consent checkbox with links + "Hesap oluştur". Note under CTA: "Devam etmek için e-postanı doğrulaman gerekecek."
3. **/forgot-password** — email + "Sıfırlama bağlantısı gönder"; success confirmation inline.
4. **/reset-password** — new password ×2 with strength meter, "Şifreyi güncelle"; expired-link error variant.
5. **/check-email** — pulse-motif mail illustration, "E-postanı kontrol et", resend (with 60s cooldown chip), "Yanlış adres mi? Değiştir".
6. **/verify-email** — three variants: verifying (spinner), success (check icon; CTA "Giriş yap"), expired/invalid (resend CTA).
7. **/terms & /privacy** — one legal template: 720px reading column, headline-lg title, sticky left mini-TOC, body-lg 16/1.6, last-updated line. Privacy page gets callout cards for: photo metadata stripping, no movement history, data deletion.

---

## 9. SCREENS — WEB APP CORE (page "03 Web App", 1440 frames; also 390 mobile-web variants for map, spot detail, upload, notifications)

### 9.1 /map — the product home (design this FIRST; it sets the aesthetic)
Full-bleed MapLibre canvas (light, desaturated basemap — the UI supplies the color). Glass top nav. Floating **glass search pill** top-left with place typeahead, geolocate, and two compact controls: radius chip ("1200 m · Seviye 3") and result-limit chip — both hint at levels ("Daha geniş arama için Seviye 4"). Left 400px **glass results panel**: filter row (status, vehicle, distance sort) + spot cards (component §7.6) with Freshness Rings live. Map shows status-pill markers; selected marker pulses and syncs with a **selected-spot preview card** docked bottom-center. Bottom-right control stack (locate, zoom). Optional **Smart Return banner** variant pinned under the nav.
**States to design:** default (6 sample spots from §13) · empty ("Bu bölgede şu an aktif yer yok." + pulse empty-state + "İlk paylaşan sen ol" CTA) · locating/loading skeleton · daily-view-limit reached (gentle wall card: "Bugünlük görüntüleme hakkın doldu · Seviye 3'te 120 görüntüleme") · mobile-web bottom-sheet variant (collapsed + expanded).

### 9.2 /spots/:spotId — spot detail
Two-column: LEFT — photo hero (single image, 16px radius, glass trust badge, Freshness Ring 64px + "07:42 kaldı" countdown-lg overlay bottom-right) above the **community signal** section: verification timeline (§7.11), verifier count, "Şüpheli mi görünüyor? Bildir" link. RIGHT — sticky action card: status badge, big countdown, address + mini location map, attribute chips (vehicle fit, context, legal "Belirsiz — topluluk bildirimi, garanti değildir" microcopy), contributor block (avatar, name, trust ring small), then actions: primary **"Doğrula"** (opens result choice: Müsait / Dolmuş / Geçersiz / Riskli / Araç boyutu uymuyor), tonal **"Park ettim"** (claim → confirm modal "Bu yeri aldığını işaretle · Paylaşan +30, sen +10 puan"), ghost **"Bildir"** (reason sheet, §13 reasons).
**States:** Active · Verified (emerald accents, "2 kişi doğruladı") · Owner view of PENDING_VALIDATION ("Fotoğrafın kontrol ediliyor — genellikle 1 dakikadan kısa sürer", actions disabled) · PENDING_REVIEW ("İncelemede — moderatör onayı bekleniyor") · Suspicious · Filled (desaturated photo, "Bu yer alındı") · Expired · Owner view of REJECTED (reason + points penalty note + appeal link).

### 9.3 /upload — 4-step spot wizard (each step = one frame)
Centered 640px card on `background`, wizard progress on top, "Kaydetmeden çıkarsan taslağın kaybolur" guard modal as extra frame.
- **Step 1 Fotoğraf:** large drag-drop zone (camera icon, "Tek bir fotoğraf yükle — park yerini net göster"), file states: uploading (progress), scanning ("Güvenlik taraması…"), ready (thumb + replace). Helper: "Konum bilgisi (EXIF) sunucuda otomatik silinir."
- **Step 2 Konum:** place search + interactive map with center teardrop pin + "Pin'i sürükleyerek düzelt" + manual lat/lng accordion.
- **Step 3 Detaylar:** description textarea (≤1000 counter) · vehicle-fit chip group (Sedan, Hatchback, SUV, Van, Motosiklet, Tüm araçlar — multi) · context select (Cadde üstü, Açık otopark, Kapalı otopark, AVM otoparkı, Konut bölgesi, Ofis bölgesi, Bilinmiyor) · legal segmented control (Yasal görünüyor / Emin değilim / **Riskli** — choosing Riskli shows inline stop card: "Riskli yerler paylaşılamaz" and blocks continue) · optional advisory flags checklist (Park yasağı levhası, Garaj girişi, Otobüs durağı, Yaya geçidi, Yangın musluğu, Kaldırım üstü, Trafiği engelliyor, Özel mülk, Diğer).
- **Step 4 Önizleme:** the actual spot card rendered as it will appear + edit links per section + primary "Paylaş".
- **Success:** celebration banner (§7.19) "+5 puan · Yerini paylaştın!", then immediately the honest gate state: "Fotoğrafın doğrulanıyor — onaylanınca haritada görünecek" with a subtle indeterminate Freshness Ring. Secondary CTA "Park yerlerime git".

### 9.4 /my-spots
Toolbar (status filter chips + sort) over spot-card grid; each card shows status badge prominently, countdown if alive, and per-status footers (e.g., Rejected → "İtiraz et"). **Empty state:** pulse illustration, "Henüz yer paylaşmadın." + "İlk yerini paylaş" CTA.

### 9.5 /profile — settings & impact hub
Identity hero: avatar, name, joined date, three stat chips (Trust ring mini · Seviye · Puan). Below, left anchor rail (Hesap · Araç · Bildirimler · Akıllı Dönüş · Güven & İlerleme) + section cards:
- Hesap: email (verified chip), change password, session list with "Diğer oturumları kapat", delete-account danger zone.
- Araç: vehicle type select (drives default fit filter).
- Bildirimler: per-type toggles (matrix: type × kanal In-app/Push/E-posta).
- Akıllı Dönüş (feature-flagged section, "Beta" chip): master toggle, home location picker (mini map), default return time, reminder lead slider (5–120 dk), plain-language privacy card: "Hareket geçmişin kaydedilmez. Sadece ayarladığın saate yakın, evinin çevresindeki gerçek yerleri kontrol ederiz."
- Güven & İlerleme: trust ring large + band explanation + recent trust changes list.

### 9.6 /gamification — "Katkıların"
Hero: level progress bar + **radius diagram** (concentric circles: "Şu an 1200 m görüyorsun · Seviye 4'te 1800 m"). Current benefits card (radius, sonuç sayısı, günlük görüntüleme, öncelik perkleri). Recent point transactions list (+20 Yerin doğrulandı · +5 Paylaşım · −25 Reddedildi …). Full **level roadmap** L1→L5 as a horizontal stepper with per-level perk cards (numbers from §13.4; L2–L4 values marked `~` sample). No streaks, no badges.

### 9.7 /leaderboard
"En çok katkı verenler" — podium top-3 (medals, tonal elevation), list rows (rank, avatar, name, trust-band chip, points), current-user row pinned/highlighted, "Daha fazla göster" pagination. Period label "Tüm zamanlar".

### 9.8 /notifications
Filter chips (Tümü · Okunmamış · Moderasyon · Oyunlaştırma), "Tümünü okundu işaretle", notification cards (§7.12) using §13.6 samples. Empty state variant.

### 9.9 /reports — "Bildirimlerim & itirazlar"
Two tabs: Gönderdiğim bildirimler (list: target spot thumb, reason, case status badge OPEN/IN_REVIEW/RESOLVED) · Cezalarım & itirazlar (violation entries with "İtiraz et" → appeal modal: textarea + submit; resolved states accepted/rejected).

---

## 10. SCREENS — ADMIN & MODERATION (page "04 Admin", 1440, denser but same tokens)

1. **/admin/moderation** — tri-pane: LEFT queue filters (status Open/In review/Resolved, severity Low→Critical with colored dots, target type) + case list rows (severity dot, target thumb, reason, age, assignee avatar). CENTER case detail: evidence (spot photo + AI verdict chip "AI: WARNING — fotoğraf belirsiz", report entries, reporter trust), spot snapshot. RIGHT action rail: Assign to me · Approve/Dismiss · Reject spot · Mark risky · (admin-only, visually separated:) Reduce trust −10 · Deduct points · Suspend user — with confirm modals. Include an **appeal-review** variant frame.
2. **/admin (Dashboard)** — KPI row (Bugün paylaşılan 112 · Şu an aktif 34 · Doğrulama oranı %61 · Ortalama yaşam 14 dk · Açık vaka 7) + activity feed + moderation load mini-chart. KPIs are counts and rates — **never revenue**.
3. **/admin/users** — table (avatar, email, role chip, trust band, points, status, joined) with search + role/status filters; **user detail** frame: profile header, trust/points history, sessions with "Revoke", role grant control, suspend/restore with reason modal, violation ledger.
4. **/admin/security** — snapshot cards: active sessions, failed logins (24 h), recent role grants, recent suspensions.
5. **/admin/analytics** — platform KPIs + the **parking funnel**: Oluşturuldu 112 → Yayında 96 → Doğrulandı 58 → Alındı 41 → Reddedildi 9 (horizontal funnel bars in blue ramp) + daily snapshot table + per-user metrics table.
6. **/admin/audit** — table: time, actor, action, target, trace-id (mono), filterable.
7. **/admin/system** — service health cards (gateway, auth, parking, media, ai-validation…), version chips, last deploy.

---

## 11. SCREENS — LANDING parkio.dev (page "05 Landing", 1440 + 390)

Tone: concise, confident, calm, honest. **No city name, no fake numbers, no urgency.** Nav: logo · Nasıl çalışır · Güven & Gizlilik · SSS · CTA "Bekleme listesine katıl".

1. **Hero (the thesis — NOT a template hero):** headline "Gerçek sürücülerden gerçek park zekâsı." / EN "Parking intelligence powered by real drivers." + honest status line "Kapalı beta — tek bir pilot bölgede, sınırlı test grubuyla." + waitlist CTA. Visual: a live-feeling map fragment with 3 spot cards whose **Freshness Rings visibly differ** (fresh blue, aging amber, expiring red) — the product physics as the hero image. Subtle float animation.
2. **Problem:** three tight statements over tonal cards (stale guesses · minutes-level change · local knowledge evaporates) — written as observations, not pain-point clichés.
3. **Solution loop:** the 5-step loop (Paylaş → Keşfet → Güven → Temiz tut → Dön) as a horizontal pulse-connected diagram.
4. **How it works:** 4-step stepper Find / Share / Verify / Return with small product-true UI vignettes (reuse real components; no fake screens with prices).
5. **Feature groups:** verification & trust · levels that widen your map (radius diagram!) · Smart Return (opt-in) · moderation & AI photo gate (advisory).
6. **Trust & privacy section:** "Gözetim için değil, güven için." Cards: photo EXIF stripped · no movement history · you control notifications · data deletion on request.
7. **Technology credibility:** quiet monospace strip (PostGIS nearby search · signed photo URLs · AI photo gate · event-driven moderation) — engineering honesty as marketing.
8. **Hosted beta expectations:** plain card: what testers get, what's not ready yet.
9. **FAQ:** Ücretli mi? (Hayır — beta tamamen ücretsiz, uygulamada ödeme yoktur.) · Konumumu sürekli takip ediyor musunuz? (Hayır — hareket geçmişi tutulmaz.) · Verilerimi silebilir miyim? (Evet.) · Yasal garanti veriyor musunuz? (Hayır — topluluk sinyali, garanti değil.)
10. **Waitlist form:** email + optional city + role select (Sürücü / Test kullanıcısı / Partner) + consent checkbox + "Katıl". Success state variant.
11. **Footer:** diligence links (Dokümantasyon, Teknik özet, İletişim, Gizlilik, Şartlar), locale switch, wordmark.

---

## 12. SCREENS — MOBILE APP (pages "06 Mobile Light" + "07 Mobile Dark", 393×852)

Design ALL below in light; then re-skin these eight in dark: map, spot bottom sheet, spot detail, share/camera, upload success, impact, notifications, profile.

### 12.1 Onboarding & first run
1. Language select (TR default preselected / EN) with logo + pulse backdrop.
2–4. Three value slides using the pulse motif (no stock illustration): "Yakınındaki gerçek park yerlerini gör" · "Fotoğrafla paylaş, topluluk doğrulasın" · "Seviye atla, daha uzağı gör" (radius circles).
5–7. Permission priming cards BEFORE system dialogs: Konum ("Yakınındaki yerleri göstermek için — sadece uygulamayı kullanırken") · Bildirimler ("Yerin doğrulanınca ve Akıllı Dönüş için") · Kamera ("Park yerini paylaşmak için"). Each with "İzin ver" + "Şimdi değil".
8. Auth landing: "Giriş yap" / "Hesap oluştur" (email-only; no social).

### 12.2 Auth (mobile)
Login · Register (with consent + verification note) · Forgot · Check-email · Verify success. Compact single-column versions of §8.

### 12.3 Map tab
Full-screen map, glass floating search pill top, "Bu bölgede ara" chip appears after pan, recent-searches sheet from search focus, radius/level chip, locate FAB, Smart Return banner variant, **permission card** variant (location off → inline card with enable CTA). Spot markers with Freshness Rings; tapping opens the **spot bottom sheet** (peek: photo thumb + ring + title + distance + 2 actions; expanded: full evidence stack + Doğrula / Park ettim / Bildir). States: default, empty, sheet-peek, sheet-expanded, permission-card, view-limit.

### 12.4 Spot detail (full screen)
Photo hero with ring+countdown overlay, status badge, attribute chips, verification timeline, contributor block, sticky bottom glass action bar (Doğrula · Park ettim · Bildir). Same status variants as web (§9.2) — design at least Verified, Pending-validation (owner), Filled.

### 12.5 Share flow (camera-first — the money flow, design every step)
1. Source sheet: "Kamerayla çek" (primary) / "Galeriden seç".
2. In-app camera: framing guide + hint "Park yerini net göster."
3. Preview & prep: retake / use, auto-compress note.
4. Upload progress: ring progress on thumb, cancel, **retry** state, **offline** state ("Bağlantı yok — bağlanınca otomatik yüklenecek" with queued chip).
5. GPS gate: accuracy card ("Konum hassasiyeti düşük (±40 m) — daha iyi sinyal için birkaç saniye bekle") with live accuracy readout; pass state.
6. Location adjust: map with fixed center teardrop pin, address preview, "Pin'i kaydırarak düzelt".
7. Details: same fields as web step 3, mobile-optimized (chip groups, segmented legal control with Riskli block state).
8. Review: rendered spot card + "Paylaş".
9. Success: celebration + "+5 puan" + pending-validation honest state.
10. Draft persistence: returning-user state "Yarım kalan paylaşımın var — devam et / sil".

### 12.6 My spots · Leaderboard · Impact
Mobile versions of §9.4 / §9.7 / §9.6 — Impact keeps the radius diagram front and center.

### 12.7 Notifications & Reports
Feed with filter chips + push-style cards; reports list + appeal sheet.

### 12.8 Smart Return
Settings screen (toggle, home picker, return time, lead slider, privacy card) · **Today card** on map/home ("Akıllı Dönüş 18:30 · 25 dk önce hatırlatacağız") · **Morning prompt modal**: "Bugün arabayla mı çıktın?" — buttons "Evet, arabayla" / "Hayır" / editable time "Tahmini dönüş 18:30" · Result notification state "Evinin yakınında 2 yer açıldı → Haritada gör".

### 12.9 Profile hub & subscreens
Hub (identity + trust/level/points, menu list) · Araç · Bildirim tercihleri · Şifre değiştir · Hakkında (version, licenses) · Oturumlar.

### 12.10 Staff (moderator role only)
Moderation queue list → case detail (evidence, actions per role) → compact analytics summary. No full admin panel on mobile.

---

## 13. SAMPLE CONTENT PACK — USE VERBATIM, NO LOREM IPSUM

### 13.1 Sample spots (İzmir — in-app only)
1. **Alsancak — Kıbrıs Şehitleri yan sokağı** · "Eczanenin önü az önce boşaldı, sedan rahat sığar. Gölgede." · Cadde üstü · Sedan · Yasal görünüyor · **07:42 kaldı** · 2 doğrulama · Mert K. (Yüksek güven, Sv 4) · 350 m
2. **Karşıyaka Çarşı — 1720 Sk.** · "Fırının karşısı, dar ama hatchback girer." · Cadde üstü · Hatchback · Belirsiz · **03:10 kaldı** · 1 doğrulama · Elif A. (Orta güven, Sv 2) · 600 m
3. **Bostanlı Sahil** · "Sahil otoparkı girişine 50 m, geniş alan." · Açık otopark · Tüm araçlar · Yasal görünüyor · **12:55 kaldı** · Doğrulandı · Baran T. (Yüksek güven, Sv 5) · 900 m
4. **Konak Pier açık otopark** · "Deniz tarafında birkaç boş yer var." · Açık otopark · SUV · Yasal görünüyor · **09:20 kaldı** · Zeynep Ş. (Orta güven, Sv 3)
5. **Forum Bornova — D blok** · "Açık alan, gölgede köşe." · AVM otoparkı · Tüm araçlar · **14:31 kaldı** · Onur D. (Düşük güven, Sv 1)
6. **Bayraklı — Folkart arka cadde** · "Ofis çıkışı boşaldı." · Ofis bölgesi · Sedan · Belirsiz · **Şüpheli** durum örneği · Deniz Y.

### 13.2 Status labels (TR / EN)
Aktif/Active · Doğrulandı/Verified · İncelemede/In review · Doğrulama bekliyor/Pending validation · Şüpheli/Suspicious · Doluldu/Filled · Süresi doldu/Expired · Reddedildi/Rejected.

### 13.3 Actions & options
Doğrula → sonuçlar: Müsait · Dolmuş · Geçersiz · Riskli/Yasadışı · Araç boyutu uymuyor. Park ettim (claim). Bildir → nedenler: Sahte/eski fotoğraf · Yanlış konum · Park yeri değil · Riskli/yasadışı · Araç boyutu yanlış · Özel mülk · Spam · Hakaret/istismar.

### 13.4 Levels (L1 & L5 are real backend values; L2–L4 are sample interpolations — keep editable)
Sv1 **300 m · 3 sonuç · 20 görüntüleme/gün** · Sv2 ~600 m · ~6 · ~50 · Sv3 ~1200 m · ~12 · ~120 · Sv4 ~1800 m · ~18 · ~200 + doğrulanmış yer önceliği · Sv5 **2500 m · 25 · 300** + bildirim önceliği.

### 13.5 Points & trust (real)
Paylaşım +5 · Yerin doğrulandı +20 · Doğrulama yaptın +5 · Yerin alındı +30 · Yer aldın +10 · Reddedilen/riskli paylaşım −25 (puan 0'ın altına düşmez). Güven puanı 100'den başlar: doğrulanan yer +2 · alınan yer +1 · moderatör reddi −10 · yönetici cezası −15. Bantlar: Güvenilmez <25 · Düşük · Orta · Yüksek ≥75.

### 13.6 Notification samples
LEVEL_UP: "Seviye 3'e ulaştın! Arama yarıçapın artık 1200 m." · POINT_EARNED: "+20 puan — Alsancak'taki yerin doğrulandı." · SMART_RETURN_PROMPT: "Bugün arabayla mı çıktın?" · SMART_RETURN_AVAILABLE: "Evinin yakınında 2 park yeri açıldı." · WARNING: "Paylaşımın kaldırıldı: Yanlış konum. −25 puan. İtiraz edebilirsin." · SYSTEM: "İtirazın kabul edildi — hesabın yeniden açıldı."

### 13.7 Freshness lines
"Az önce paylaşıldı" · "3 dk önce paylaşıldı" · "12 dk önce doğrulandı" · "07:42 kaldı" · "Son 2 dakika".

---

## 14. ACCESSIBILITY & i18n RULES (apply everywhere)

- Body text ≥16px on marketing and content pages; WCAG AA contrast on all text and status colors; visible 2px focus rings; touch targets ≥44px.
- Status is never color-only: icon + label always. Countdown ring always paired with numeric time.
- Reduced motion: every animation (pulse, shimmer, spring) has a static equivalent.
- TR default everywhere; design must survive +20–30% string growth (test chips and buttons with the longest TR labels, e.g., "Doğrulama bekliyor", "Bekleme listesine katıl"). Never bake text into imagery.
- Numbers/dates: 24-hour clock, Europe/Istanbul assumptions in samples.

---

## 15. DEFINITION OF DONE — SELF-CHECK BEFORE FINISHING ANY PAGE

☐ Zero currency symbols, prices, "Book/Reserve", or revenue visuals anywhere
☐ Zero social-login buttons; zero streaks/badges/achievements; one photo per spot everywhere
☐ Every spot representation shows: Freshness Ring + countdown + last-verified + trust
☐ Every status appears as icon + label with correct semantic color
☐ Turkish strings used from §13; layouts don't break at +25% length
☐ Map chrome uses the glass recipe; sections use tonal ramp, not bordered boxes
☐ Landing carries honest hosted-beta framing; no city named on landing; no fake proof
☐ Pending-validation / pending-review / rejected / suspicious moments are designed, not skipped
☐ Privacy explained in-context (EXIF stripping, no movement history, Smart Return copy)
☐ Level = sight radius is visualized at least on Impact + map radius chip + onboarding slide
☐ Dark mode (mobile only) is calm navy — no black, no neon
☐ The pulse motif is the only illustration system; no emoji, no stock art
☐ Empty, loading, and error (with trace-id) states exist for every data surface
☐ Frames named per §6.1 and organized into the 8 Pencil pages
