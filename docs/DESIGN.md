# AgentCall Android UI — Design System

Status: approved (2026-08-19). This is the style reference for the UI redesign
pass. Screen-by-screen layout plans live below; implementation happens one
screen at a time.

## Design Read

> precision instrument + "The Control Board" (machined graphite, indigo
> action, phosphor readouts, status lamps) — operator-panel identity on
> Home/Settings, but the two call screens dial *down* the machinery and dial
> *up* identity and calm, because a call is the human moment, not the
> instrument moment.

Dials: DESIGN_VARIANCE 2–3 · MOTION_INTENSITY 2–3 · VISUAL_DENSITY 6–7.

## 1. Palette

| Role | Value | Used for |
|---|---|---|
| Background | `#0D0F12` (Slate850) | app ground |
| Plate | `#1B1E23` (Slate800) @ ~0.9 alpha + 1dp `#2E3238` (Slate700) hairline | all cards/plates — one recipe, no exceptions |
| Raised | `#20242A` (Slate750) | pressed, selected nav, input containers |
| Primary action | `#6C7CFF` (Indigo500) / `Indigo600` | Connect, Add AI, Save, Send, confirm |
| Phosphor | `#35E0FF` | **live state only**: waveform, selected nav, live readouts |
| Lamp green | `#3DDC84` | online / connected / live |
| Lamp amber | `#FFB020` | busy / connecting / reconnecting / warning |
| Lamp red | `#FF3B30` | destructive only: End, Delete, errors |
| Lamp unlit | `#3A3F46` | offline / idle (this is "gray = idle") |
| Text | `Slate50` / `Slate400` / `Slate500` | primary / secondary / tertiary |

Rules: gradients are for avatars and call-identity moments only — never on
cards or buttons. Status colors carry meaning and are never decorative.
Shadows only on sheets and dialogs; cards use hairlines.

## 2. Typography

- **Display & headline roles (displayLarge–headlineSmall):** Fraunces
  (SIL OFL 1.1), Black 900, variation settings `wght=900, opsz=36` —
  the serif identity for "AGENTCALL", "Settings", caller names.
  Bundled: `res/font/fraunces_variable.ttf`, license in `docs/fonts/OFL-Fraunces.txt`.
- **Body (title/body/label):** Roboto default, unchanged.
- **Data readouts (mono):** `MonoBody` / `MonoLabel` / `MonoTitle` for
  timers, latencies, last-seen, host/URLs, version, connection status.

| Role | Style |
|---|---|
| Page header | headlineMedium, Black, caps |
| Section label | labelSmall, Medium, 1.2sp tracking, caps |
| Card title | titleMedium, SemiBold |
| Body | bodyMedium / bodySmall |
| Data readout | MonoBody / MonoLabel |

## 3. Spacing & radius

Base grid 4dp. Screen horizontal padding 20dp. Section gap 24dp top / 10dp
below label. Card inner padding 16dp. Grid gaps 12dp. Touch targets ≥ 44dp.

| Token | Value | Applied to |
|---|---|---|
| R0 Pill | 4dp | status pills, nav keys, small chips |
| R1 Plate | 8dp | home agent cards, code snippets |
| R2 Control | 12dp | text fields, buttons, banners |
| R3 Panel | 16dp | settings cards, context cards |
| R4 Sheet | 20dp | Later-picker sheet |
| Full | circle | avatars, call actions, End button |

Tokens: `Radii` (ui/theme/Shape.kt), `Spacing` (ui/theme/Dimens.kt).

## 4. Icons & components

Material Icons (filled), single family, `material-icons-extended` (already a
dependency). Sizes: 18–20dp rows, 22dp call controls, 24dp nav. Active =
accent tint, inactive = Slate400.

Shared components (ui/composables/): `StatusPill`, `Plate`, `SectionLabel`,
`Notice`, `ActionCircle`, plus existing `GradientAvatar`, `AmbientBackground`.

## 5. Screen plans

### Home
- Header: "AGENTCALL" + v1.0 (serif, headlineMedium), shared `StatusPill`
  top-right (green pulse CONNECTED / amber pulse RECONNECTING / unlit OFFLINE).
- Agent cards (R1 plate, hairline, press 0.96): line 1 name; line 2 lamp +
  status word (Online/Busy/Offline); line 3 mono recency, always present —
  "Last seen 12m ago" / "Last call 2d ago" / "No calls yet". Tap opens profile.

### Settings
- Header strip unchanged content; spacing aligned to Home.
- Server Connection: promoted status row — 12dp lamp, mono status readout
  (exact ViewModel strings), hairline, then host field + Connect/Reset + test.
- Sections: unified `SectionLabel` + plate recipe, 24dp separation.
- Collapsible by default: **Call Messages, About, Network Info** (chevron +
  smooth expand; content untouched). Others always open.

### Active call
- Top bar: agent name + lamp + mono timer. Banners via shared `Notice`:
  "not responding" / "agent offline" = quiet indigo surface + slate lamp
  (informational, not alarm); reconnecting = amber (genuine warning).
- Context card kept (Indigo900 tint). Transcript geometry unchanged; avatars
  via `GradientAvatar`.
- Controls: four `ActionCircle` 48dp (Record/Stop, Mute/Unmute, Speaker,
  Repeat — strings unchanged). End: 56dp red circle + red halo ring,
  unchanged position.

### Incoming call
- Padding 32→24dp; avatar 120dp (icon 60dp), pulse rings + glow kept; name
  headlineMedium serif.
- Countdown: mono "Auto-decline in Xs" + thin progress arc around avatar,
  single neutral color.
- Buttons: Answer 76dp solid green (most prominent), Decline 64dp glass red,
  Later 64dp glass white — same order/icons/handlers. Later sheet R4.

## 6. Non-goals

No behavior, state, callback, string, screen, setting, button, or navigation
change. No backend/FCM/liveness/signaling code. Profile screen inherits
tokens only.
