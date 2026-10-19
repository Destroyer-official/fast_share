# FastShare — "Aurora Glass" UI/UX Redesign Plan

A ground-up redesign of the FastShare Android UI, fusing seven design systems into one
coherent language. Every style is assigned a **specific job** where it is strongest, and
every known weakness is **neutralized by another system**. No decoration without function.

---

## 1. Research Summary (what each system is good at, and where it fails)

| System | Core strength | Known weakness (from research) | Our countermeasure |
|---|---|---|---|
| **Apple Liquid Glass** (iOS 26) | Floating navigation/control layer that refracts, catches specular highlights, adapts tint | Legibility failures in early betas; Apple added tinted/reduce-transparency fallbacks. HIG rule: glass is *only* for the floating control layer, never content | Glass used only on the floating header + bottom dock; ≥ 72% opacity tint, 1px specular edge, bold foreground text, never on content cards |
| **Material 3 Expressive** (Android 16) | Spring motion physics, emphasized typography, shape system, larger targets — users find key actions up to 4× faster | Shape/motion overload becomes chaotic (Google's own warning: use shapes sparingly) | Restrained 4-step radius scale; springs only for press/tab/state confirmation; one hero action per screen |
| **Fluid Bento Grids** | Asymmetric tiles group related info, scannable, hierarchy by size not noise | Can fragment into visual noise | Strict 2-column grid with hero spans; consistent 12dp gutters; every tile is one idea |
| **Claymorphism** | Puffy opaque cards floating above the bg — keeps *real* contrast (it exists precisely because neumorphism failed contrast) | Can look toy-like/unprofessional if pastel-on-white | Deep-space canvas + muted jewel accents + crisp type = premium, not playful. Clay reserved for content cards only |
| **Neumorphism** | Beautiful inset wells and pressed states | Structural WCAG contrast failure; controls unfindable ("ghost UI") | Used *only* for inset surfaces (search field) and pressed states — never standalone buttons, never load-bearing contrast |
| **Skeuomorphism (2026 "craft" form)** | One light source, specular top-edge lines, shadow stacks = instant perceived quality | Old skeuo was heavy, cluttered, textured | Only the 7 craft moves' cheap ones: 1–2px specular highlights, layered shadows, z-stacking. No textures, no literal objects |
| **Generative Zero-UI / Spatial** | Anticipatory, context-aware, calm; depth without VR | Discoverability: invisible features don't exist | Every implicit behavior (auto-server, auto-scan) is mirrored by a visible control and a live status surface. Depth = elevation tiers, not gimmicks |

Sources: Apple HIG Liquid Glass analyses, Google Material 3 Expressive research (46 studies / 18k
participants), Smashing Magazine & IxDF on neumorphism contrast failure, setproduct/superdesign
claymorphism recipes, 2026–27 trend reports (bento as structure, calm design, spatial depth).

---

## 2. Design Philosophy — "One Light, Three Layers"

The whole UI obeys a single physical metaphor: **a deep aurora space, with glass instruments
floating above it and clay content resting between.**

- **One light source**: top-left. Every highlight, shadow, and gradient agrees with it.
- **Layer 0 — Environment (Skeuo/Spatial)**: deep-space aurora gradient canvas. Gives glass
  something to refract and clay something to float over. OLED-friendly dark-first.
- **Layer 1 — Content (Clay + M3 + Bento)**: opaque, softly-puffed bento cards with real
  contrast, tinted drop shadows, inner top highlight. Files, devices, telemetry.
- **Layer 2 — Instruments (Liquid Glass)**: floating header capsule + floating bottom dock.
  Translucent, specular-edged, tinted. The only glass in the app.

**Zero-UI behaviors** (already in the product, now made visible as design):
server auto-starts, radar auto-scans, hero status card always answers
"what is happening right now" without the user asking.

---

## 3. Design Tokens

### Color — "Aurora Night" (dark-first, light variant retained)

| Token | Dark | Role |
|---|---|---|
| `space_bg` | `#070B14` | Environment base |
| `aurora_cyan / violet / teal` | `#1E5EFF / #7A4DFF / #0FB5A8` @ 10–16% α | Ambient mesh glows |
| `glass_panel` | `#B3141E30` (70%) | Floating instruments |
| `glass_stroke` | `#2EFFFFFF` top / `#14FFFFFF` bottom | Specular edge gradient |
| `clay_card` | `#121A2E` | Content cards |
| `clay_inner_hi` | `#1FFFFFFF` | Inner top highlight (clay puff) |
| `ink_primary / secondary / faint` | `#F2F6FF / #9AA7C7 / #5F6B8A` | Type ramp (≥7:1 on cards) |
| `accent_pulse` | `#4D7CFE` | Primary action (electric blue) |
| `accent_mint / amber / coral` | `#2BD9A9 / #FFB547 / #FF6E6E` | Semantic states |

Light mode: inverted luminance, same hues; glass opacity raised to 78%.

### Shape (M3 Expressive scale, restrained)

`radius_xs 10` · `radius_sm 14` · `radius_md 20` · `radius_lg 28` · `radius_full 999`
Cards = 20, hero tiles = 28, pills/dock/header = full, thumbs = 14.

### Elevation (spatial z-tiers)

`z0 canvas` → `z1 card (2dp + tinted shadow)` → `z2 sheet (6dp)` → `z3 glass instruments (10dp + ambient glow)`.

### Motion (spring physics, no easing curves)

- Press: scale 0.96, 120ms spring-out (OvershootInterpolator 2.0 on release)
- Tab switch: 260ms overshoot on indicator, content fade 150ms
- Sheet/dialog: 280ms decelerate + slight overshoot settle
- Radar pulse: 1.6s breathing loop (the only ambient animation)

### Typography

System sans (SF-like on device), weights 400/500/700. Hero numerals 28sp bold,
titles 15sp semibold, meta 11sp medium with 0.06 letter-spacing for caps labels.

---

## 4. Screen-by-Screen Blueprint

### 4.1 Root scaffold (`activity_main.xml` — all existing view IDs preserved)

1. **Layer 0**: full-bleed `aurora_canvas` drawable (3-stop vertical gradient + two radial glows).
2. **Floating glass header** (top, capsule, status-bar inset aware):
   - Home orb button (glass chip), `statusDot` + `statusText` (live server address),
     `tvNearbySummary` pill → Radar, `btnHost` (copy), `btnPower` (tint shifts mint↔coral),
     `btnServerSheet` (settings gear).
3. **Tab content zone** (files / radar / transfers) — plain container, content scrolls *under* glass.
4. **Server sheet** (`serverSheet`): clay elevated sheet, 28dp top radius, drag handle,
   neu-inset input fields, clay primary save button.
5. **Floating glass dock** (bottom): three tab capsules + **center raised clay FAB** (`btnNavUpload`,
   gradient electric-blue, 56dp, the single hero action — M3 Expressive key-action principle).
6. **Permission gate** (`gate`): full-screen bento arrangement — hero shield tile + three
   permission tiles (`rowFiles`, `rowBattery`, `rowNotif`) with state badges + clay CTA (`btnGrant`).

### 4.2 Files tab

- **Hero folder tile** (bento span-2, 28dp): `tvFolderTitle`, `tvFolderMeta`, `tvItemCountBadge`
  pill, `btnUp` glass chip.
- **Breadcrumb rail** (`rvBreadcrumbs`): horizontal glass chips, active crumb tinted.
- **Neu-inset search well** (`etSearch`): pressed-into-surface look — the one legitimate
  neumorphic surface; inner shadow top-left dark / bottom-right light, accent caret, ≥7:1 text.
- **Control chips**: `btnViewMode` (grid/list morph icon), `btnSort` — glass chips with spring press.
- **File grid/list items**: clay cards, 14dp thumb well with inset shade, video badge glass chip,
  action pill tonal clay. Press = 0.97 scale spring + card shadow deepen.

### 4.3 Radar tab

- **Radar hero** (bento span-2): breathing concentric pulse rings (only ambient animation),
  `tvDeviceCountBadge`, `btnRefreshRadar` clay tonal button, `radarSearchingView` inline state.
- **Device cards** (`item_device_card.xml`): clay card, OS avatar in tonal orb, ONLINE badge,
  mono IP, two actions: gradient primary `btnSendToDevice` + tonal `btnBrowseDevice`.

### 4.4 Transfers tab

- **Bento telemetry**: span-2 server status tile (`tvServerStatusSummary`), two half tiles
  for `tvSessionDown` / `tvSessionUp` with oversized numerals (M3 emphasized type),
  access tile (`tvAccessSummary`).

### 4.5 Media preview dialog

Near-black glass scrim, floating glass top bar (icon, name, meta, close orb),
content on pure black (glass never touches media — Apple rule), bottom floating
gradient share pill (`btnShareFile`).

---

## 5. Implementation Map (files → changes)

| File | Change |
|---|---|
| `values/colors.xml` + `values-night/colors.xml` | Full Aurora Night token set (both modes) |
| `values/themes.xml`, `values-v31/themes.xml` | M3 theme rebinding, edge-to-edge, dark status icons per mode |
| `values/dimens.xml` | Radius/spacing/elevation token scale |
| `res/drawable/` (~26 files) | New material system: `aurora_canvas`, `glass_panel_header`, `glass_panel_dock`, `glass_chip`, `clay_card`, `clay_card_hero`, `clay_button_primary` (gradient), `clay_button_tonal`, `neu_inset_well`, `tab_pill_active`, `fab_upload_gradient`, status dots, badges, progress, scrim. **Drawable names referenced from Java are kept** (`glass_tab_active_bg`, `glass_tab_inactive_bg`, `dot_on`, `dot_off`, `glass_badge_*`, `glass_circle_btn`, `glass_pill_cyan`) so code compiles unchanged |
| `layout/activity_main.xml` | Full rewrite — same IDs, new structure (glass header, bento tabs, floating dock) |
| `layout/item_file_grid.xml`, `item_file_list.xml` | Clay cards, inset thumb wells — same IDs |
| `layout/item_device_card.xml` | Clay device card — same IDs |
| `layout/item_breadcrumb.xml` | Glass crumb chips — same IDs |
| `layout/dialog_media_preview.xml` | Floating-glass lightbox — same IDs |
| `MainActivity.java` | Add spring press animations, tab indicator motion, radar pulse, haptic ticks — **no ID/logic changes** |

Compatibility: minSdk 26 — all effects done with layered gradient drawables (no RenderEffect /
real-time blur dependency), so the glass look is simulated with tint + specular strokes and
stays 120fps-cheap. True window blur (API 31+) layered behind the dock as progressive enhancement.

## 6. Quality Bar (how we know it's "2027-grade")

1. Every screen answers "what now" in <1s (hero tiles carry live state — Zero-UI surfaced).
2. Every text/background pair ≥ 4.5:1, body ≥ 7:1 (neumo/clay weaknesses engineered out).
3. Exactly one gradient-primary action per screen (M3 Expressive key-action research).
4. All motion is spring-based, ≤350ms, interruptible.
5. Glass never overlaps content; content never relies on shadow alone for state.
6. Dark and light modes both first-class; OLED-true black base in dark.
