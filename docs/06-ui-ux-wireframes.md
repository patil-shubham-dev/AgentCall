# UI/UX Wireframes & Design Specifications

## AgentCall MCP

**Version:** 1.0
**Status:** Draft

---

## 1. Design Principles

- **Minimal interruption:** Calls are intentional, not intrusive
- **AI transparency:** Users always know why they're being called
- **Mobile-first:** Primary interface is the mobile app
- **Privacy-respecting:** Clear indicators for recording/mute state

---

## 2. Screen Map

```
┌───────────────────────────────────────────┐
│               AUTH FLOW                    │
├───────────────────────────────────────────┤
│  Splash → Login → Device Pairing → Home   │
└───────────────────────────────────────────┘

┌───────────────────────────────────────────┐
│              MAIN SCREENS                  │
├───────────────────────────────────────────┤
│  Home (Call History)                       │
│  ├─ Active Call Bar (sticky bottom)       │
│  └─ Call History List                      │
│                                           │
│  Settings                                  │
│  ├─ Profile                               │
│  ├─ Notifications                         │
│  ├─ Do Not Disturb Schedule               │
│  ├─ Voice & Audio                         │
│  ├─ Privacy                               │
│  └─ Connected Agents                      │
│                                           │
│  Incoming Call (overlay, all apps)        │
│  Active Call (full screen)                │
│  Call Complete (summary)                  │
│                                           │
│  Agent Management (web/optional)          │
│  └─ API Key Management                    │
└───────────────────────────────────────────┘
```

---

## 3. Screen Specifications

### 3.1 Splash Screen

```
┌──────────────────────────┐
│                          │
│                          │
│        [Logo]            │
│   AgentCall MCP   │
│                          │
│    Connecting you with   │
│    your AI agents...     │
│                          │
│                          │
│   ┌──────────────────┐  │
│   │  Sign in with     │  │
│   │  Google           │  │
│   └──────────────────┘  │
│                          │
│   ┌──────────────────┐  │
│   │  Sign in with     │  │
│   │  GitHub            │  │
│   └──────────────────┘  │
│                          │
│   ┌──────────────────┐  │
│   │  Sign in with     │  │
│   │  Apple            │  │
│   └──────────────────┘  │
│                          │
│   ┌──────────────────┐  │
│   │  Pair Device      │  │
│   └──────────────────┘  │
│                          │
└──────────────────────────┘
```

**States:**
- Loading: Spinner while checking stored auth
- Unauthenticated: OAuth buttons
- Authenticated but no agent configured: Setup prompt

---

### 3.2 Device Pairing Screen

```
┌──────────────────────────┐
│ ← Back                   │
│                          │
│    Pair Your Device      │
│                          │
│  1. Open the web         │
│     dashboard            │
│                          │
│  2. Go to Settings →     │
│     Devices → Add        │
│                          │
│  3. Scan this QR code:   │
│                          │
│      ┌───────────┐       │
│      │ ██ █ ██   │       │
│      │ █ █ █ █ █ │       │
│      │ ██ █ ██   │       │
│      │ █ █ █ █ █ │       │
│      │ ██ █ ██   │       │
│      └───────────┘       │
│                          │
│     Code expires in      │
│     4:32                 │
│                          │
│   ┌──────────────────┐  │
│   │  Refresh Code     │  │
│   └──────────────────┘  │
│                          │
│   Or enter pairing code  │
│   [ _ _ _ _ _ _ ]       │
│                          │
└──────────────────────────┘
```

---

### 3.3 Home Screen (Call History)

```
┌──────────────────────────┐
│  12:34        Internet   │
│  Calling MCP             │
│                          │
│  ┌──────────────────────┐│
│  │ ⚪ Active Call       ││
│  │ "AI Agent - Task #42"││
│  │ 01:23 ● Live         ││
│  │ [Tap to open]        ││
│  └──────────────────────┘│
│                          │
│  Recent Calls            │
│                          │
│  ┌──────────────────────┐│
│  │ Today                ││
│  │ ✓ AI Agent - Deploy  ││
│  │   2:30 PM · 45s     ││
│  │   Need approval to   ││
│  │   deploy to prod     ││
│  ├──────────────────────┤│
│  │ ✓ Code Review Agent  ││
│  │   11:15 AM · 2m 12s ││
│  │   "3 issues found"  ││
│  ├──────────────────────┤│
│  │ ✗ AI Agent #2       ││
│  │   10:00 AM · Missed ││
│  │   "Database schema" ││
│  └──────────────────────┘│
│                          │
│  ┌──────────────────────┐│
│  │ Yesterday            ││
│  │ ...                  ││
│  └──────────────────────┘│
│                          │
│  [Bottom Nav: Home |     │
│   Settings]              │
└──────────────────────────┘
```

**Key UI elements:**
- Active call bar persists at top (yellow/orange background)
- Call history items grouped by date
- Each item shows: call status (✓ completed, ✗ missed, ◷ cancelled), agent name, time, duration, context
- Swipe left to dismiss / delete
- Tap to see call summary

---

### 3.4 Incoming Call Screen (Overlay)

```
┌──────────────────────────┐
│                          │
│                          │
│                          │
│       [AI Icon]          │
│                          │
│    Incoming AI Call      │
│                          │
│    AI Agent - Task #42   │
│                          │
│    "Need approval to     │
│    deploy version 2.1    │
│    to production"        │
│                          │
│     Priority: High       │
│                          │
│                          │
│     ╔═══════════════╗    │
│     ║   Decline  ║   ║  │
│     ╚═══════════════╝    │
│           Answer         │
│                          │
│    [Snooze 5 min]        │
│                          │
└──────────────────────────┘
```

**Platform-specific:**
- Android: Full-screen intent + heads-up notification
- iOS: CallKit native UI (system call screen) with CXProvider

**Key elements:**
- Large AI agent avatar/icon
- Agent name and task reference
- Context summary (truncated to 2 lines)
- Priority badge (color-coded: low=gray, normal=blue, high=orange, urgent=red)
- Answer button (green) + Decline button (red)
- Snooze option (send back to queue with reminder)

---

### 3.5 Active Call Screen

```
┌──────────────────────────┐
│                          │
│    00:45                 │
│                          │
│       [AI Icon]          │
│                          │
│    AI Agent - Task #42   │
│                          │
│    "Need approval to     │
│    deploy version 2.1    │
│    to production"        │
│                          │
│    ┌──────────────────┐  │
│    │ ████████░░ 85%   │  │
│    │ Voice quality    │  │
│    └──────────────────┘  │
│                          │
│                          │
│   [Mute] [Speaker] [End] │
│                          │
└──────────────────────────┘
```

**Key elements:**
- Timer at top
- Agent identity + context summary (persistent reminder of why call was initiated)
- Voice quality indicator (real-time: packet loss, jitter)
- Mute button (toggle, red when muted)
- Speakerphone toggle
- End call button (red, prominent)
- Network indicator (WiFi/Cellular, signal strength)
- (Future) Live captions toggle

**Audio feedback:**
- When agent is "speaking" (TTS playing): subtle waveform animation
- When user is speaking: microphone icon with level meter
- Mute confirmation toast when toggling

---

### 3.6 Call Complete Screen

```
┌──────────────────────────┐
│                          │
│     ✓ Call Complete      │
│                          │
│    Duration: 0:45        │
│                          │
│    ┌──────────────────┐  │
│    │ Call Summary     │  │
│    │                  │  │
│    │ User approved    │  │
│    │ deployment to    │  │
│    │ production.      │  │
│    │                  │  │
│    │ Response: "Yes,  │  │
│    │ go ahead with    │  │
│    │ the deploy"      │  │
│    └──────────────────┘  │
│                          │
│    Quality: Excellent    │
│    Network: WiFi 5GHz   │
│                          │
│   ┌──────────────────┐  │
│   │  View in History  │  │
│   └──────────────────┘  │
│                          │
│   ┌──────────────────┐  │
│   │       Done        │  │
│   └──────────────────┘  │
│                          │
└──────────────────────────┘
```

**States:**
- Normal end: Show summary
- Missed call: Show "Call Missed — AI was informed you're unavailable"
- Dropped call: Show "Call Dropped — AI has been notified"
- Declined: Show "Declined — AI has been notified to find another way"

---

### 3.7 Settings Screen

```
┌──────────────────────────┐
│ ← Back         Settings  │
│                          │
│  Profile                 │
│  ┌──────────────────────┐│
│  │ Name: John Doe      ││
│  │ Email: j@d.com      ││
│  │ [Edit Profile]      ││
│  └──────────────────────┘│
│                          │
│  Notifications           │
│  ┌──────────────────────┐│
│  │ ✓ Incoming calls    ││
│  │ ✓ Task completions  ││
│  │ ✗ Marketing         ││
│  │ Sound: Default      ││
│  └──────────────────────┘│
│                          │
│  Do Not Disturb          │
│  ┌──────────────────────┐│
│  │ ○ Off                ││
│  │ ○ Smart (auto)      ││
│  │ ● Scheduled          ││
│  │   From: 22:00        ││
│  │   To:   07:00        ││
│  │   Timezone: UTC      ││
│  └──────────────────────┘│
│                          │
│  Connected Agents        │
│  ┌──────────────────────┐│
│  │ OpenCode ● Active   ││
│  │ Claude Code ● Active││
│  │ [Add Agent]         ││
│  └──────────────────────┘│
│                          │
│  Privacy                 │
│  ┌──────────────────────┐│
│  │ ○ Store transcripts ││
│  │   (off by default)  ││
│  │ ○ Store recordings  ││
│  │   (off by default)  ││
│  │ [Clear History]     ││
│  └──────────────────────┘│
│                          │
│  About                   │
│  ┌──────────────────────┐│
│  │ Version 1.0.0       ││
│  │ Licenses            ││
│  │ Terms of Service    ││
│  │ Privacy Policy      ││
│  └──────────────────────┘│
└──────────────────────────┘
```

---

## 4. Visual Design Specs

### 4.1 Color Palette

| Token | Hex | Usage |
|-------|-----|-------|
| Primary | `#6366F1` (Indigo 500) | Brand, primary buttons |
| Primary Dark | `#4F46E5` (Indigo 600) | Pressed state |
| Success | `#22C55E` (Green 500) | Answer, connected |
| Error | `#EF4444` (Red 500) | End call, decline, errors |
| Warning | `#F59E0B` (Amber 500) | Priority high, attention |
| Background | `#0F172A` (Slate 900) | Dark background |
| Surface | `#1E293B` (Slate 800) | Cards, sheets |
| Surface Elevated | `#334155` (Slate 700) | Raised elements |
| Text Primary | `#F8FAFC` (Slate 50) | Body text |
| Text Secondary | `#94A3B8` (Slate 400) | Caption, metadata |
| Call Active Bar | `#F59E0B` (Amber) @ 15% opacity | Active call banner |

### 4.2 Typography

| Style | Size | Weight | Usage |
|-------|------|--------|-------|
| Headline | 24px | Bold (700) | Screen titles |
| Title | 18px | Semi-bold (600) | Caller name |
| Body | 16px | Regular (400) | Context summary |
| Caption | 14px | Regular (400) | Timestamps, metadata |
| Timer | 48px | Light (300) | Call timer (monospace) |

### 4.3 Spacing

- Base unit: 4px
- Padding: 16px (screens), 12px (cards)
- Corner radius: 12px (cards), 24px (bottom sheets), 9999px (buttons)
- Icon size: 24dp (standard), 48dp (caller avatar), 72dp (incoming call avatar)

### 4.4 Dark Mode

- Default: Dark mode only (mobile calls typically happen in dark environments)
- Future: Light mode toggle (Settings → Appearance)
- System-based detection (follow system theme)

---

## 5. Micro-interactions & Animations

| Interaction | Animation | Duration |
|-------------|-----------|----------|
| Incoming call | Slide up from bottom + subtle pulse on avatar | 300ms |
| Answer call | Scale up + fade transition to active call | 200ms |
| End call | Fade out + slide down | 200ms |
| Mute toggle | Icon cross-fade, subtle haptic | 100ms |
| Call timer | Count-up, smooth digit transitions | Continuous |
| Quality indicator | Smooth bar animation for packet loss | Real-time |
| Screen transition | Push/slide (iOS standard), fade (Android) | 350ms |

---

## 6. Web Client (Basic)

### 6.1 Scope
- Login and device pairing only (MVP)
- Call history viewing
- Agent management (API keys)
- Settings

### 6.2 Responsive Breakpoints
- Mobile web: <768px (same flows as native, browser-based)
- Desktop: ≥768px (sidebar layout)

### 6.3 Tech
- React + Tailwind CSS + shadcn/ui
- No WebRTC in MVP web client (incoming calls redirect to mobile)
