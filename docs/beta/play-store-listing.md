# Parkio — Play Store Listing & Data Safety (Internal Testing)

## App identity

- **App name:** Parkio
- **Package:** `dev.parkio.app`
- **Category:** Maps & Navigation
- **Short description (≤80 chars):**
  > Find and share free street parking near you — powered by drivers like you.
- **Full description:**
  > Parkio helps drivers find parking faster. See parking spots shared moments ago
  > by other drivers near you, and give back by sharing the spot you're leaving —
  > snap a photo, confirm the location, done.
  >
  > • Live map of nearby shared spots
  > • Share a spot in under 30 seconds: photo → pin → publish
  > • Smart Return: one parking check before you head home
  > • Points and trust levels for reliable sharers
  > • No ads. No precise-location tracking in the background.
- **Keywords (ASO):** parking, street parking, find parking, share parking, park, spot

## Permissions & rationale (user-facing strings already in `app.json`)

| Permission | Why | When asked |
|---|---|---|
| Camera | Capture the parking-spot photo | First "Take photo" |
| Photo library (Android Photo Picker — no broad media permission) | Choose an existing spot photo | First "Choose from gallery" |
| Location (fine + coarse, while-in-use only) | Place the spot where you stand; show spots near you | First map / spot creation |
| Notifications | Nearby-spot and account notifications | First app start after login |
| `RECORD_AUDIO` | **Blocked** in `app.json` (`blockedPermissions`) — the app never records audio | — |

No background location. No contacts, SMS, call log, or storage-wide access.

## Data Safety questionnaire answers

| Question | Answer |
|---|---|
| Does the app collect or share user data? | Collects; does **not** share with third parties |
| Location — approximate & precise | Collected while in use, for app functionality (spot placement / nearby search). Not shared. Precise coordinates are sent only to Parkio's own backend when the user publishes a spot; analytics events never include coordinates |
| Photos | User-selected photos are uploaded to create a spot (app functionality). EXIF is stripped and images re-encoded on device before upload |
| Personal info (email, display name) | Account management. Not shared |
| Device or other IDs (push token) | App functionality (push delivery). Deactivated on logout |
| Crash logs / diagnostics | Planned via Firebase Crashlytics (dev build); currently buffered locally only |
| Data encrypted in transit? | Yes (TLS on hosted environments) |
| Can users request deletion? | Yes — account deletion via support during beta (self-serve deletion is a pre-GA requirement) |

## Store assets checklist

- [x] App icon 512×512 (`assets/images/icon.png` source)
- [x] Adaptive icon foreground + brand background `#0A2540`
- [x] Splash screen (brand navy, centered logomark)
- [ ] Feature graphic 1024×500 — **needs design artwork**
- [ ] ≥2 phone screenshots from a physical device (emulator evidence exists; Play prefers real captures)
- [ ] Hosted privacy-policy URL (draft below)

## Privacy policy (draft to host)

> **Parkio Privacy Policy (Beta)** — Parkio collects the minimum data needed to
> run a community parking map: your account email and display name; parking-spot
> photos you choose to publish (EXIF-stripped); the location of spots you publish;
> your approximate location while using the map; a push token if you enable
> notifications; and basic usage events (e.g. "spot created") that never contain
> your coordinates. Data is stored on Parkio's servers, encrypted in transit, and
> never sold or shared with third parties. Log out to deactivate push delivery to
> your device. To delete your account and data during beta, contact
> privacy@parkio.dev. This policy will be versioned before public release.
