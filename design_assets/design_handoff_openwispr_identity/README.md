# Handoff: OpenWispr Logo & Icon Identity

## Overview
Brand identity exploration for **OpenWispr** — an open-source, locally-run voice-dictation app (a Wispr Flow alternative where models run on-device). This package contains three logo/icon directions on a soft, muted "sunset" palette, with app-icon, horizontal-lockup, dark-mode, palette, and type specs.

## About the Design Files
The file in this bundle (`OpenWispr Identity.dc.html`) is a **design reference created in HTML** — a presentation board showing the intended marks, colors, and type, not production code to ship directly. The task is to extract the assets below (SVG marks, palette, type) and implement them in the target environment: export the chosen mark as standalone SVG/PNG/ICNS assets, and build the wordmark/lockup with the codebase's existing component and asset conventions. If there's no app shell yet, drop the SVGs into `/assets` (or equivalent) and reference them normally.

## Fidelity
**High-fidelity.** Colors, geometry, stroke weights, and typography are final-candidate values. Reproduce the chosen mark's SVG path data exactly; the palette hex/oklch values below are authoritative.

## The Three Directions

All marks are drawn on a **`0 0 100 100` viewBox**, `fill:none`, `stroke-linecap:round`, `stroke-linejoin:round`, `stroke:currentColor` (set color via the parent / CSS `color`). Recommended primary is **01 · Aperture**.

### 01 · Aperture  *(recommended / primary)*
Concept: the open "O" of *Open* — a mouth mid-whisper with a breath escaping.
Stroke width: **7**.
```svg
<svg viewBox="0 0 100 100" fill="none" stroke="currentColor" stroke-width="7"
     stroke-linecap="round" stroke-linejoin="round">
  <path d="M67,29 A25,25 0 1 0 67,71" />
  <path d="M69,50 C75,44 79,56 86,49" />
</svg>
```

### 02 · Breeze
Concept: soft breath lines — speech as light and open as wind.
Stroke width: **6**.
```svg
<svg viewBox="0 0 100 100" fill="none" stroke="currentColor" stroke-width="6"
     stroke-linecap="round" stroke-linejoin="round">
  <path d="M30,35 C42,28 54,28 66,35" />
  <path d="M20,50 C38,41 62,41 80,50" />
  <path d="M34,65 C44,59 54,59 64,65" />
</svg>
```

### 03 · Eddy
Concept: a single open coil of breath that never quite closes.
Stroke width: **7**.
```svg
<svg viewBox="0 0 100 100" fill="none" stroke="currentColor" stroke-width="7"
     stroke-linecap="round" stroke-linejoin="round">
  <path d="M61,33 C45,27 32,39 34,53 C36,68 55,74 65,61 C71,53 67,46 58,48" />
</svg>
```

## App Icon Spec
- **Shape:** rounded square (squircle-style). Radius ≈ **22.7% of side** (e.g. 30px on a 132px icon, 22px on a 96px icon). For Apple platforms, mask with the official squircle / use a 1024px master and let the OS apply the superellipse.
- **Mark size:** the SVG occupies **~58% of the icon** (centered).
- **Mark color:** Cream `oklch(0.985 0.012 82)` ≈ `#FCF8F4`.
- **Flat variant:** solid background = Coral `oklch(0.71 0.13 40)` ≈ `#E07B52`.
- **Gradient variant:** `linear-gradient(140deg, oklch(0.82 0.11 74) → oklch(0.71 0.13 42) @52% → oklch(0.59 0.12 17))` ≈ `#F0A573 → #DF7C53 → #B05A55`.
- **Drop shadow (on light):** `0 8px 20px -10px rgba(150,60,30,0.7)`. On dark showcase: `0 18px 40px -14px rgba(60,25,20,0.9)`.

## Horizontal Lockup
- Layout: `display:flex; align-items:center; gap:20px` → `[mark] [wordmark]`.
- Mark height ≈ **1.2× the wordmark cap height** (e.g. 46px mark with 38px wordmark).
- Wordmark: text **"OpenWispr"**, exactly as written (mixed case, one weight).
- On light: mark = Coral, wordmark = Ink. On dark: both = Cream.
- Clear space: keep at least the mark's width of padding around the full lockup.

## Typography
- **Wordmark / UI:** **Mulish** (Google Fonts). Weights **500 / 600**. Wordmark uses **600**, `letter-spacing: -0.02em`.
- **Labels / specs / code:** **IBM Plex Mono**, weight 400–500, uppercase with `letter-spacing` 0.12–0.22em for eyebrows.
- Type scale used on the board: wordmark 58px (header) / 38px (lockup) / 54px (dark); body 18px; captions 13.5px; mono labels 10–12px.

## Design Tokens (Sunset palette)
| Role  | oklch | approx hex | Usage |
|-------|-------|-----------|-------|
| Amber | `oklch(0.80 0.11 70)`  | `#EDB079` | gradient start, accents |
| Coral | `oklch(0.71 0.13 40)`  | `#E07B52` | **primary accent / mark** |
| Rose  | `oklch(0.61 0.12 20)`  | `#C16560` | gradient end-mid |
| Plum  | `oklch(0.33 0.06 28)`  | `#4A2E27` | dark backgrounds, deep ink |
| Cream (bg)   | `oklch(0.972 0.014 78)` | `#F6EFE6` | page background |
| Card / white | `oklch(0.995 0.006 85)` | `#FFFDFA` | surfaces |
| Mark cream   | `oklch(0.985 0.012 82)` | `#FCF8F4` | mark on color |
| Ink          | `oklch(0.34 0.03 47)`   | `#4B3A2E` | body / wordmark on light |
| Ink soft     | `oklch(0.53 0.03 50)`   | `#7E6B5B` | secondary text |
| Hairline     | `oklch(0.91 0.012 72)`  | `#E7DECF` | borders/dividers |
| Tint panel   | `oklch(0.955 0.022 66)` | `#F3E6D6` | mono-mark backdrop |

> Prefer the **oklch** values as source of truth; the hex column is an sRGB approximation for tools that don't support oklch.

Other tokens:
- **Radii:** icon 22.7% of side; cards/surfaces 18px; inner panels 12px.
- **Gradient (linear, 140deg):** Amber→Coral(52%)→Rose. Horizontal swatch on the board uses 90deg.

## Assets
- No raster/photographic assets — all marks are vector (SVG path data above).
- Fonts loaded from Google Fonts: Mulish, IBM Plex Mono. In a real app, self-host or import via the codebase's font pipeline.
- **To produce deliverable assets:** copy the chosen `<svg>` out of the design file, save as `openwispr-mark.svg`; render flat + gradient icon masters at 1024px and export the platform sizes (favicon `.ico`/PNG 16–512, macOS `.icns`, etc.).

## Files
- `OpenWispr Identity.dc.html` — the full presentation board (header, 3 concepts × {mono mark, flat icon, gradient icon}, lockups, dark showcase, palette, type). Open it in a browser to inspect any mark at any size; all geometry is inline SVG.
