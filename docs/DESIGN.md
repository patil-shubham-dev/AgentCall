# AgentCall Android UI — Design System

Status: approved (2026-08-30 — updated from 2026-08-19). HTML `tmp/agentcall-design/index.html` is the spec; this doc mirrors it. Implementation happens one screen at a time.

## Design Read

> minimal premium (Claude-inspired) — warm paper + ink, one neutral stack, one primary, semantic each ONE meaning. Operator-panel identity dialed *down* on call screens, identity and calm dialed *up* — a call is the human moment, not the instrument moment.

Dials: DESIGN_VARIANCE 2–3 · MOTION_INTENSITY 2–3 · VISUAL_DENSITY 6–7. Light is default; dark is true near-black.

## 1. Palette — paper + ink (exact `ui/theme/Color.kt` vs HTML `:root`)

| Role | Value | Used for |
|---|---|---|
| Background | `#FAF9F7` `LightBg` | app ground (warm paper) |
| Surface | `#FFFFFF` `LightSurface` | cards/plates |
| SurfaceVariant | `#F2F2F0` `LightSurfaceVariant` | raised / input / chips |
| SurfaceElevated | `#EBEBEA` `LightSurfaceElevated` | pressed elevated |
| Border | `#E8E8E6` `LightBorder` | hairline 1dp |
| BorderStrong | `#DDDDDB` `LightBorderStrong` | strong hairline |
| Text primary | `#111111` `LightTextPrimary` / `Ink` | primary text / primary action |
| Text secondary | `#6B6B6B` `LightTextSecondary` | secondary |
| Text tertiary | `#9A9A9A` `LightTextTertiary` | tertiary / hint |
| Ink | `#111111` | Add AI, Connect, Save, Send, confirm (ONE accent) |
| OnInk | `#FAF9F7` | on primary |
| Success | `#1A7F4B` `Success` / `SuccessBg #E8F5E9` | online / completed only |
| Warning | `#B45309` `Warning` / `#FEF3C7` | busy / connecting / reconnecting / Later |
| Error | `#DC2626` `Error` / `#FEE2E2` | destructive only: End, Delete, errors |
| Offline | `#D1D1CF` `DotOffline` | offline / idle (gray = idle) |

Rules: gradients only on avatars/identity moments — never on cards/buttons. Status colors single meaning, never decorative. Shadows only on sheets/dialogs; cards use 1dp hairline. Dark: `DarkBg #0F0F0F` / `DarkSurface #1A1A1A` etc.

## 2. Typography

- **Display & headline (displayLarge–headlineSmall):** Fraunces Black 900 `wght=900 opsz=36` — wordmark "AgentCall", caller names. Bundled `res/font/fraunces_variable.ttf`.
- **Body (title/body/label):** Inter 400/500/600 — card titles, body, labels. System default maps to Inter via HTML; Roboto fallback unchanged.
- **Data readouts (mono):** `MonoBody`/`MonoLabel`/`MonoTitle` JetBrains Mono — timers, latencies, last-seen, host/URLs.

| Role | Style |
|---|---|
| Page header | headlineMedium, Black, caps |
| Section label | labelSmall, Medium, 0.4sp tracking, caps 11sp |
| Card title | titleMedium, SemiBold 15sp |
| Body | bodyMedium 14/20 / bodySmall 13/18 |
| Data readout | MonoBody 12 / MonoLabel 11 0.8 / mono 12 |

## 3. Spacing & radius — `Spacing` / `Radii` (`ui/theme/Dimens.kt`, `Shape.kt`)

Base grid 4dp. Screen horizontal 20dp (`XL`), section gap 24dp top / 10dp below label (`SectionLabelGap`), card inner 16dp (`CardPadding L`), grid gaps 12dp (`GridGap M`), list gaps 10dp (`ListGap`), touch ≥48dp.

| Token | Value | Applied to |
|---|---|---|
| Pill | 999dp | status pills, nav keys, chips |
| Card | 12dp | home agent cards |
| Panel | 16dp | settings cards, context cards |
| Field | 12dp | text fields, buttons, banners |
| Sheet | 20dp | Later-picker sheet |
| Full | circle | avatars (40/48/64), dial 148/86, End |

CSS → Compose: `--radius-pill 999`→`Radii.Pill`, `--radius-card 12`→`Radii.Card`, etc.; `--bg`→`ColorScheme.background`.

## 4. Icons & components

Material Icons filled, single family (`material-icons-extended`). Sizes: 18–20dp rows, 18dp inline controls, 22dp call controls, 24dp nav. Active = `primary`/`onPrimary` tint, inactive = `onSurfaceVariant`.

Shared `ui/composables/`: `StatusPill`, `Plate` (`Radii.Card/Panel` + hairline), `SectionLabel`, `Notice` (amber warn vs slate info), `InlineControl` (48dp `Field` 5-inline), `CallSwipeDial` (148 outer / 86 core, 56dp threshold, spring 0.6/420, `LongPress` haptic, `ringPulse 2.2s`), `GradientAvatar` (identity only), `WaveformBar` minimal (3dp bars, `onSurfaceVariant` low-contrast, no glow).

## 5. Screen plans — as built (HTML `tmp/agentcall-design/index.html`)

### Home — single list, initials avatar, pure status board
- Header `AgentCall` `displayLarge` Fraunces + `dot Ready·v1.0 labelSmall`, single `⚙ 40dp surface` (Add lives in Settings only). `outline 0.6` divider, `surfaceVariant` battery banner.
- Agent cards `Radii.Card 12` hairline `1dp outline` `surface`: `40dp Ink` circle initial `titleMedium OnInk SemiBold`, name `titleMedium`, meta `6dp dot + status word + · recency labelSmall` (dot color `Success/Warning/DotOffline`, word colored when online/busy). `› 20dp 0.5 alpha`. Tap→profile, long-press→delete. `LazyColumn Spacing.ListGap 10` `ScreenPadding 20` bottom `XXL 24`.
- States: populated / empty (`64dp surfaceVariant` `SmartToy 28dp` → `Ink` initial in final, `titleLarge` + one-liner `bodyMedium` + `Go to Settings -> Add AI` primary) / disconnected (`64dp WarningBg/ErrorBg` `WifiOff 28dp Error`, `Could not reach host MonoLabel`, `Retry` primary + `Outlined Open Settings`) / loading skeleton `surfaceVariant` shimmer hairline.

### Settings — unified plate recipe, collapsible groups
- Header `Settings headlineMedium` + `Configure your connection bodySmall`. `ScreenPadding 20` `SectionGap 24`.
- Server Connection: `12dp dot Success/Warning/DotOffline` pulse, `Backend Server titleMedium` + `MonoLabel Connected/Connecting…` `1dp Slate700 0.6` divider, host `OutlinedTextField 12` `surface` + `Computer Indigo400` leading, `Connect Ink` + `Reset` ghost, `Test Connection` speed row + `Ping` result `Green/Red`.
- Sections `SectionLabel labelSmall caps 0.4` → `Plate Panel 16 hairline`: **Collapsible by default: Call Messages, Quiet Hours, Network Info, About** (chevron + `expandVertically+fadeIn`). Others open. `Quiet Hours` `Start 22:00 End 07:00 Mono 13` `Edit hours` ghost + `Disable` subtle. `Call Messages` `Decline` + `Call-back-later {X}` editors `surfaceVariant` `Field 12`. `AI Connections` list + `Add AI Ink` (sole entry). `Network Info` `HTTP API / WebSocket / Connection Type` rows. `Privacy & Data` `surfaceVariant` note. `About` `Version 1.0 / Stack / Security`.

### Incoming call — swipe dial
- `16dp` horizontal pad, `12dp` pill `Incoming call` + `Mono Auto-decline in 42s`, `160dp` pulse Canvas (`outline 0.15*(1-p)`) + `112dp surfaceVariant` `Call 48dp onSurfaceVariant` + `120dp` progress arc `2dp onSurfaceVariant 0.4`, `180dp` → `name titleLarge` Fraunces, `ClientBadge`, context `Panel 16 surface 1dp outline` `AI is calling about tag 11 caps` + `bodyMedium` + chips `Pill surface 1dp outline`.
- Dial: `148dp surface 1dp outlineStrong shadow 12/30` + `ringPulse` two rings `1dp outline 0.18*(1-p) scale 0.92→1.18`; core `86dp surfaceVariant 1dp outline` → `Success/Error/Warning` + white icon on drag past `56dp` threshold (`spring 0.6/420`, `LongPress` haptic once per direction, label reveal `Answer/Decline/Later labelMedium SemiBold` top-center, snap-back under threshold fires nothing). `Up→onAnswer`, `Left→onDecline`, `Right→onLater->{showLaterPicker=true}`. Later picker `Radii.Card 12` `1dp outline surface` `18dp` radio + `Call me back in X min` primary (collapsible, not sheet).

### Active call — 5-inline, minimal waveform
- Top `8dp dot Success/Warning` + `name titleMedium` + `Mono 02:14·Speaking/Reconnecting…` + `ClientBadge`. Banners `Notice`: `Reconnecting — stays live` amber `WarningBg` (warn), `AI not responding/offline` slate `surfaceVariant` (info), `paused` dashed.
- Context `surface 12dp 1dp outline` `AI is calling about`. Transcript `LazyColumn 8dp` bubbles `16dp (4dp tail)` `Slate800/Indigo800` etc., `GradientAvatar 32dp` / `Slate700 Person`. Failed `WarningBg 0.15` `Not sent — tap to retry`. Typing `GradientAvatar 28dp` + `Slate800` dots.
- Waveform minimal `40dp 20dp pad` `3dp bar 3dp gap` centered `onSurfaceVariant 0.35+0.5*level` active else `0.18+0.15*level`, no glow — secondary to label.
- Controls `Row SpacedBy 8dp`: 4× `InlineControl weight 1f 48dp Field 12 surfaceVariant 1dp outline` idle → `primary onPrimary` active (`Rec/Stop`, `Mute/Unmute`, `Spk`, `Rpt`) + End `weight 1f 48dp Field 12 Error 6dp ErrorBg` halo `PhoneForwarded 18dp` + `End` white `labelMedium SemiBold`. Input `surfaceVariant Field 12` + `Send Ink circle 48dp`, `QuickReply Chips` `Pill`.

### Battery Help — plate recipe, no ambient
- Header `Call Reliability headlineMedium` + `Keep reachable bodySmall`, sections `Battery Optimization` 12dp dot `Success/Warning` + `CheckCircle/BatteryAlert 22dp`, `Request battery exemption Ink` + `Open phone settings Ink` + `Guided Outlined`, local log `surfaceVariant 0.4` + `ExpandLess/More Indigo400`.

### Notifications — shade, copy/icon/tint only (channels unchanged)
- Incoming full-screen `INK A` + `Claude is calling` + `Answer primary` / `Decline Error` / `Later` ghost, ongoing `Success dot` `On call`, missed `ErrorBg ◎` `Missed`, signaling quiet `surfaceVariant ◐` `Listening` — tints `ink/success/error/neutral`, no gradients.

## 6. Non-goals

No behavior/state/callback/string/screen/setting/button/navigation change beyond dial input surface → same intents (`ACTION_START_CALL/_CANCEL/_SCHEDULE_CALLBACK`) with same `RINGING+not-resolved` guards. No backend/FCM/liveness/signaling, Room, MCP, `ApiClient` init order, FGS types beyond rendering. Profile inherits tokens only.

Tokens: `Radii` (`Shape.kt`), `Spacing` (`Dimens.kt`). Source of truth is HTML `tmp/agentcall-design/index.html`; this doc mirrors it.
