# Color System

This is the canonical color direction for future UI and marketing implementation. Values are design tokens, not final CSS.

## Palette Goals

- Calm and trustworthy.
- Modern but not cold.
- Mobility-adjacent without copying map brands.
- Accessible in light mode first.
- Dark mode supported but not primary brand identity.

## Core Tokens

| Role | Token | Hex | Use |
| --- | --- | --- | --- |
| Primary | Parkio Teal | `#147C72` | Primary actions, active states, brand anchors |
| Primary Dark | Deep Teal | `#0E5F58` | Hover/pressed states, strong headings on light surfaces |
| Primary Light | Mist Teal | `#DDF5F1` | Soft highlights, selected states |
| Secondary | Mobility Blue | `#2563A6` | Links, technical credibility, secondary actions |
| Accent | Signal Lime | `#A3C94A` | Positive signal accents, small highlights only |
| Success | Verified Green | `#1F8A5B` | Verified spots, success feedback |
| Warning | Curb Amber | `#C47A18` | Risk, caution, restricted status |
| Danger | Report Red | `#C2413A` | Errors, rejected spots, destructive actions |
| Neutral 900 | Ink | `#17211F` | Primary text |
| Neutral 700 | Slate Moss | `#3D4A47` | Secondary text |
| Neutral 500 | Soft Slate | `#6D7A76` | Metadata |
| Neutral 300 | Line | `#CBD7D3` | Borders and dividers |
| Neutral 100 | Cloud | `#F2F6F4` | Subtle surfaces |
| Surface | White | `#FFFFFF` | Cards and page surfaces |
| Background | Warm Off White | `#F8FAF8` | Page background |

## Text Hierarchy

- Primary text: `#17211F`.
- Secondary text: `#3D4A47`.
- Muted text: `#6D7A76`.
- Inverse text: `#FFFFFF`.
- Link text: `#2563A6`, underline on hover/focus.

## Status Colors

- Available/verified: Verified Green.
- Claimed: Mobility Blue.
- Restricted/uncertain: Curb Amber.
- Suspicious/rejected/error: Report Red.
- Draft/unknown: Neutral 500.

## Dark Mode Principles

Dark mode should be calm and readable, not hacker-like.

Suggested dark tokens:

- Background: `#101816`.
- Surface: `#18221F`.
- Elevated surface: `#20302B`.
- Primary text: `#EEF7F4`.
- Secondary text: `#B8C7C2`.
- Primary accent: `#42B8A8`.
- Border: `#33433E`.

Avoid pure black backgrounds and neon accents.

## Accessibility

- Primary text on background must target WCAG AA minimum contrast.
- Primary buttons should use white text on Parkio Teal or Deep Teal.
- Accent Lime should not carry critical text alone; use it as a small signal color.
- Status colors must be paired with labels or icons, never color alone.
- Focus states should be visible and high contrast.

## Usage Ratios

- 60 percent neutral/background.
- 25 percent white/surface.
- 10 percent primary teal.
- 5 percent secondary/accent/status.

This keeps Parkio calm and avoids a one-note palette.
