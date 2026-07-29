# Parking spot rejection reasons

Ownership model for structured rejection metadata on `parking_spots` and related APIs.

## Fields

| Field | Role |
|---|---|
| `rejection_reason_code` / API `rejection.code` | **Canonical product/business reason.** Machine-readable, provider-agnostic. |
| `rejection_source` / API `rejection.source` | **Actor/source of the decision:** `AI_POLICY`, `MODERATOR`, `SYSTEM_MIGRATION`. |
| Provider / vision reason (`reason_code:…` findings) | **Technical AI evidence only.** Mapped explicitly into a product code before persistence. Never a product API contract. |
| `rejection_reason_text` / API `rejection.message` | **Optional audit snapshot or moderator-authored explanation.** Not the canonical localization authority for known codes. |
| Frontend i18n `parking:rejection.codes.*` | **Canonical UI copy** for known product reason codes. |
| API `rejection.moderatorNote` | Moderator-authored free text when distinct from the catalog default. |

## Product vs provider reasons

`ModerationDecisionPolicy.mapRejectionReasonCode` maps provider/vision codes to product codes, for example:

- `UNRELATED_SUBJECT` → `CLEARLY_UNRELATED_CONTENT`
- `SCREENSHOT_OR_SYNTHETIC` → `SCREENSHOT_OR_DOCUMENT`
- unknown provider code → controlled fallback (`CLEARLY_UNRELATED_CONTENT`), never arbitrary propagation

Parking domain and frontend reason catalogs must not depend on Gemini-specific naming.
Raw provider payloads / prompts must not be shown to end users.

## `LEGACY_POLICY_RESET`

System migration classification (`source=SYSTEM_MIGRATION`), **not** an AI image-quality judgment.
Domain status remains `REJECTED`; UI uses a distinct display status and section title
(“Sistem politika geçişi”) so migration rows cannot be read as AI rejections.

## UI resolution order

1. Known `code` → localized i18n message
2. Else non-blank server `message` → fallback
3. Else generic `rejection.codes.UNKNOWN`

Moderator notes are shown separately and are not replaced by i18n.
