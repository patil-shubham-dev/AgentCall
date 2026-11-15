# AgentCall — Ongoing Call Stage & Composer Specification

Status: implementation reference (design-approved prototype, not yet built in the apps).
Date: 2026-11-12.
Visual source of truth: `.ui-verify/redesign/ongoing-call.html`.
Reference frames: `.ui-verify/redesign/qa/composer-*.png`, `composer-vanish-*.png`.
Applies to: Android and iOS ongoing-call screens, plus the backend events that feed them.

This document explains the screen's intent, geometry, tokens, interaction logic, animation
mechanics, accessibility rules, and the parts that must **not** be copied from the prototype.
Where the HTML and this document disagree about a visual value, the HTML wins; where they
disagree about intent, this document wins and the HTML should be corrected.

---

## 0. How to use this document

1. Read sections 1–3 for the direction and the visual system.
2. Build the static stage from sections 4–7.
3. Build the composer and send flow from sections 7–9.
4. Wire real call data using the contracts in section 13.
5. Validate against section 18 before calling it done.

The prototype is a self-contained demo. It contains a scripted conversation loop, a fake
clock, a seeded transcript, a preview status bar, a page shell, a live-reload script, and a
`window.agentcallOngoing` QA hook. None of those belong in the app (see section 19).

---

## 1. Product intent

A call is the human moment. The screen should feel like a line that is open, not a dashboard,
not a chat app, and not a control panel.

The design is built on five rules:

1. **One live line at a time.** The stage shows the current turn in large type; the previous
   turn sits above it, faint. Everything older lives in the conversation sheet.
2. **Voice is the primary interface.** Two dithered voice fields — AI hanging from the top,
   human rising from the bottom — carry presence without a waveform widget, avatar ring, or
   glowing orb.
3. **Text is on demand.** There is no permanent input. The field appears only after
   **More → Type a message**, and it disappears again when it is not needed.
4. **Controls never move.** Growing the composer borrows height from the live turn above it.
   Mute / Audio / More / End stay exactly where the thumb expects them.
5. **Motion is texture, not decoration.** The only ambient motion is the dither field and the
   one-shot dissolve on send. Nothing bounces, pulses for attention, or loops decoratively.

The visual register is near-black, low chroma, one purple for the AI, one warm paper tone for
the human, one red for ending the call. Dither is the only texture; there are no gradients,
glass panels, glows, or glossy blobs.

### UX invariants

- The stage is clean by default: no composer, no attach button, no mic button, no send disc.
- The composer is reachable in two taps (More, then Type a message) and opens with the
  keyboard already up.
- Sending never blocks on the animation. The message is committed before the first dust frame.
- A draft is never destroyed by accident: closing, blurring, opening the conversation, or
  sending another app to the background keeps it. Only Replay clears it.
- The call can always be ended in one tap, even while composing.
- Reduced motion removes the dissolve and all ambient animation without removing any function.

### Why these choices

| Choice | Reason |
|---|---|
| Hidden composer | A permanent field turns a live call into a chat window and invites typing while the agent speaks. The stage should stay a call first. |
| No send button on stage | Enter / the IME send key is the native gesture on a phone. A disc would add chrome and compete with the four call controls. |
| Four-line cap | Keeps the live caption readable and the controls fixed; longer thoughts belong in the conversation sheet. |
| Height borrowed from `.live` | The thumb targets must not move when the field grows. The live region can afford to yield space; the controls cannot. |
| Height held during the dissolve | The transcript and controls must not jump at the exact moment the user is reading their sent message. |
| Instant sheet hand-off | A fading sheet behind an already-focused field looks broken and costs the tap that opened it. |
| Rotating ghost | The field is optional and unlabeled by design; the ghost teaches its purpose without adding a label or helper text. |
| Dust instead of a slide-out | The message leaving the field should feel like the words themselves leaving, and the grain echoes the voice fields' dither. |

---

## 2. Screen anatomy

Baseline artboard: **360 × 800** (the prototype scales this to fit the browser; the app uses
the real device size). All measurements below are CSS px at 1× unless noted.

```
┌──────────────────────────────────────┐ 0
│ status bar                     26    │  OS-owned in the app
├──────────────────────────────────────┤ 26
│ ░░ AI dither field             180   │  0–180, full bleed
│                                      │
│  [mark]  ChatGPT          1:24       │  call header 208–254
│          • AI speaking               │
│                                      │
│              ChatGPT                 │  live region 254–516 idle
│     The migration finished           │  (shrinks to 152 at 4 lines)
│     cleanly. Two indexes             │
│     are still building.              │
│                                      │
│  ┌──────────────────────────────┐    │  composer 472–516 at 1 line
│  │ Message ChatGPT              │    │  (44 / 66 / 88 / 110)
│  └──────────────────────────────┘    │
│                                      │
│   (Mute)  (Audio)  (More)  (End)     │  controls 528–604
│                                      │
│ ░░ human dither field          180   │  620–800, full bleed
└──────────────────────────────────────┘ 800
```

Screen-local coordinates (0 = top of the shell, which includes the status bar). `.app` itself
starts at 26 and is 774 tall.

| Band | Value | Notes |
|---|---|---|
| Status bar | 26 | OS-owned in the app |
| App | 774 | 26 → 800 |
| Side padding | 24 | Content width 312 |
| Top voice field | 180 | 26 → 206; full bleed, canvas |
| Call header | 208 → 254 | 46 high (38 mark + two text lines); 2 px air below the field |
| Live region idle | 254 → 516 | 262 high |
| Composer | 44 / 66 / 88 / 110 | 1 / 2 / 3 / 4 lines; bottom at 516 |
| Composer gap | 12 | 516 → 528 |
| Controls | 76 | 528 → 604; 4 × 72 + 3 × 8 = 312 |
| Air below controls | 16 | 604 → 620 |
| Bottom voice field | 180 | 620 → 800 |

The bottom controls are anchored from the bottom: `margin-bottom: 196px` (180 field + 16
air), `height: 76px`. The parent is a flex column, so the flexible `.live` region absorbs the
composer's height. This is the mechanism behind "controls never move"; do not replace it with
absolute positioning without preserving the same invariant.

### Verified responsive states

| Viewport | Result |
|---|---|
| 400 × 840 | Artboard at 1×, shell 360 × 800, frames 720 × 1600 at DPR 2 |
| 360 × 800 | Shell scaled by `--s ≈ 0.911`; layout values stay 360 × 800, measured field ≈ 100 for a layout 110, gap ≈ 11 |
| 360 × 800, 4-line draft | Field 110 layout ≈ 100 measured, live 152 ≈ 138 measured, controls stay at 481 measured, no document scroll |
| 400 × 840, 4-line draft + long caption | Field 110, caption clears the field by ≥ 12, controls gap 12 |

---

## 3. Design tokens

### 3.1 Colour

| Token | Value | Role |
|---|---|---|
| `--screen-bg` | `#0E0F12` | Stage ground, true near-black |
| `--surface` | `#191B20` | Sheets, conversation panel |
| `--surface-raised` | `#202329` | Field, control discs |
| `--outline` | `rgba(255,255,255,0.12)` | Hairlines |
| `--on-surface` | `#F1F2F4` | Primary text and icons |
| `--muted` | `#9CA1AB` | Secondary text, ghost, labels |
| `--ai` | `#7967DE` | AI identity, switches, replay |
| `--ai-bright` | `#9A8BF2` | AI accent text, focus, caret, presence dot |
| `--human` | `#EFE9DF` | Human identity, warm paper |
| `--decline` | `#FF6B6B` | End call only |
| `--decline-icon` | `#16181C` | Icon on the End disc |
| `--amber` | `#E8B04B` | Muted-AI presence dot |
| muted dot | `#6E737D` | Idle / ended presence |
| secondary button fill | `#5B4BC4` | Send / Replay |
| button fill hover | `#6A5AD4` | Send / Replay hover |
| End label | `#C98282` | The word "End" under the red disc |
| ghost on field | `#9CA1AB` | Placeholder |
| scrim | `rgba(0,0,0,0.62)` | Sheet scrim |

Semantic rules:

- Purple only means AI / AI action. It is never used for the human or for generic emphasis.
- Warm paper only means the human. It is never a warning colour.
- Red only means ending the call (and the End label tint). It is not used for errors here.
- Amber only appears on the presence dot when the AI is muted.
- Grey is idle, disconnected, or ended.

Measured contrast (WCAG 2.x formula):

| Pair | Ratio |
|---|---|
| `#F1F2F4` on `#0E0F12` | 17.11:1 |
| `#9CA1AB` on `#0E0F12` | 7.39:1 |
| `#9A8BF2` on `#0E0F12` | 6.71:1 |
| `#EFE9DF` on `#0E0F12` | 15.87:1 |
| `#C98282` on `#0E0F12` | 6.39:1 |
| `#16181C` on `#FF6B6B` | 6.40:1 |
| `#9CA1AB` on `#202329` | 6.07:1 |
| `#9A8BF2` on `#202329` | 5.51:1 |

All text pairings above clear 4.5:1. Re-verify on device with the platform's display
profile; do not lighten the muted token to "improve" it without re-measuring.

### 3.2 Type

Font: **Inter** (400 / 500 / 600). The prototype loads it from Google Fonts; the app must
bundle it. System fallback: `-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif`.

| Role | Size / line | Weight | Notes |
|---|---|---|---|
| Agent name | 17 / 22 | 600 | `letter-spacing: -0.1px` |
| Presence | 12 / 16 | 500 | `letter-spacing: 0.1px` |
| Duration | 15 / 20 | 600 | Tabular numerals |
| Previous line | 14 / 20 | 400 | 2-line clamp, 0.45 opacity when present |
| Speaker label | 12 / 16 | 600 | `letter-spacing: 0.02em` |
| Live caption | 22 / 30 | 500 | `letter-spacing: -0.15px`, 3-line clamp, balanced |
| Sheet title | 20 / 26 | 600 | |
| Sheet row | 16 / 24 | 500 | |
| Sheet row value | 14 / 20 | 500 | Muted |
| Route name | 15 / 20 | 500 | |
| Route sub | 12 / 16 | 400 | Muted |
| Control label | 11 / 14 | 500 | `letter-spacing: 0.2px` |
| Composer input | 16 / 22 | 400 | Never smaller; prevents iOS zoom |
| Ghost | 16 / 22 | 400 | Muted |
| Thread message | 15 / 22 | 400 | |
| Thread meta | 12 / 16 | 600 | Name; 11/15 time |
| Ended title | 22 / 30 | 600 | |
| Ended duration | 13 / 18 | 400 | Muted |

No all-caps labels, no letter-spaced eyebrow text, no display serif on this screen. (This is
the call-screen register, deliberately quieter than the app's light paper theme.)

### 3.3 Radii, spacing, shadows

| Token | Value | Use |
|---|---|---|
| Field radius | 16 | Composer field, canvas |
| Control disc | 56 (circle) | Mute / Audio / More / End |
| End disc | 56 (circle) | `#FF6B6B` |
| Sheet top radius | 28 | More / Audio panels |
| Thread panel radius | 24 | Conversation panel |
| Small radius | 10 / 12 | Show transcript / thread input |
| Pill | 999 | Jump to live, Replay |
| Field padding | 11 top / 11 bottom, 14 sides | Text starts 14 from the edge |
| Control gap | 8 | Four 72-wide targets |
| Callout shadow | `0 30px 64px -30px rgba(0,0,0,.9), 0 0 0 1px #2A2C31` | Preview shell only |
| Sheet shadow | `0 -12px 40px rgba(0,0,0,.4)` | |
| Thread shadow | `0 -16px 44px rgba(0,0,0,.46)` | |
| Field hairline | `inset 0 0 0 1px rgba(255,255,255,.10)` | |
| Field focus | `inset 0 0 0 1px rgba(154,139,242,.55)` | |

### 3.4 Motion

| Token | Value | Use |
|---|---|---|
| `--ease-out` | `cubic-bezier(0.22, 1, 0.36, 1)` | Entrances, grows, settles |
| Composer enter | 220 ms transform / 180 ms opacity, translateY 6 → 0 | |
| Composer exit | 220 ms, then hidden | |
| Height settle | 200 ms ease-out | After the dissolve |
| Ghost fade | 180–200 ms | Rotation and show/hide |
| Control press | 120 ms scale 0.96 | |
| Sheet enter | 220 ms panel, 200 ms scrim | |
| Sheet exit | 260 ms, then hidden | |
| Thread enter | 260 ms panel / 200 ms opacity, translateY 18 → 0 | |
| Thread exit | 280 ms, then hidden | |
| Caption swap | 400 ms translateY 5 → 0 + fade | |
| Vanish sweep | 8 px per 16.67 ms ≈ 480 px/s | Time-based, not duration-based |

Reduced motion: all transitions and animations collapse to ~0 ms; the composer open/close is
instant; the ghost does not rotate; the voice fields freeze on a still frame; the dissolve is
skipped. Functionality is unchanged.

---

## 4. Voice fields (the two dither waves)

Two full-bleed canvases, each **360 × 180** CSS px, one anchored to the top of `.app`, one to
the bottom. They are painted at a **3 px cell** grid: a 120 × 60 low-resolution buffer,
nearest-neighbour upscaled to the canvas so the grain stays hard.

### Algorithm

- 8×8 Bayer matrix (the same thresholds used by the 21st.dev dithering shader's 8×8 mode):

  ```
  0 32  8 40  2 34 10 42
  48 16 56 24 50 18 58 26
  12 44  4 36 14 46  6 38
  60 28 52 20 62 30 54 22
  3 35 11 43  1 33  9 41
  51 19 59 27 49 17 57 25
  15 47  7 39 13 45  5 37
  63 31 55 23 61 29 53 21
  ```

- For each cell `(cx, cy)`, a boundary is computed from four summed sine waves. The AI field
  fills from the top down to its boundary; the human field fills from the bottom up.
- Coverage from the boundary: `cov = 0.06 + 0.88 * smoothstep(0, 0.62, d)`, where `d` is the
  normalised distance from the edge. A cell is on when `cov > BAYER[...] / 64`.
- Alpha: idle `96` → speaking `236`, interpolated by the phrase envelope.
- Idle bases: AI `rows * 0.34` (a low strip at the top), human `rows * 0.66` (a low strip at
  the bottom). Speaking bases: AI `rows * 0.80`, human `rows * 0.20`. So a speaking field
  more than doubles its height and the crest carves the edge.
- Amplitude: `0.9 + 9.2 * level`; phase speed `0.30 + 0.55 * env + 0.85 * level`.
- Envelope: phrase level `env` chases `0.55 + 0.45 * target` with a 0.16s attack / 0.34s
  release; crest `level` chases the target with 0.05s / 0.12s. This produces a body that
  swells on a phrase and a crest that moves on syllables.
- Only the current speaker's field is driven. The other field falls to idle.
- Muted: the AI field is not driven and the presence dot goes amber ("AI muted").
- Device pixel ratio is capped at **2.5** for the wave canvases (crispness vs fill cost).

The prototype synthesises the speech target with `speechLevel(t, seed)`: a slow gate, a ~4 Hz
syllable pulse, and a little jitter. In the app, replace it with the real audio envelope
(RMS or the TTS/WebRTC level) and keep the attack/release constants; the synthetic function
is demo-only.

### Reduced motion / still state

`freezeStill()` cancels the RAF, sets the active field's `env` to `0.86` and the inactive one
to `0.05`, and renders one frame. The still frame must still read as "AI above / human below".

### Native mapping

- **Android:** render a 120 × 60 `IntArray` alpha mask from the same Bayer function, upload
  to an `ImageBitmap`, and draw it scaled 3× with `FilterQuality.None`. Drive with
  `withFrameNanos`; pause with the lifecycle.
- **iOS:** render the same 120 × 60 buffer into a `CGContext`/`CGImage`, draw with
  `interpolation(.none)` in `Canvas` or a `UIImageView`; drive with `CADisplayLink` or
  `TimelineView(.animation)`.

Do not rebuild this as a gradient or a blur. The hard 3 px grain is the identity.

---

## 5. Call header and presence

Layout: 38 × 38 two-tone mark, 12 gap, name + presence stack, duration right-aligned.

Presence is a 5 px dot plus a 12/16 label. The dot transitions its background over 200 ms.

| App state | Dot | Label |
|---|---|---|
| AI speaking | `#9A8BF2` | `AI speaking` |
| AI speaking, muted | `#E8B04B` | `AI muted` |
| Human speaking | `#EFE9DF` | `Listening to you` |
| Connected, nobody speaking | `#6E737D` | `Connected` |
| Call ended | `#6E737D` | `Call ended` |

The duration is `m:ss`, tabular numerals, and is `aria-hidden` on the stage because the ended
state announces the final duration. In the app, derive it from the call's monotonic start
time, not from a UI tick that can drift when the app is backgrounded.

The header has no tap action in the prototype. If the app wants "tap the agent name to open
its profile", add it deliberately; do not make the whole header a button by accident.

---

## 6. Live transcript

Two stacked pieces:

1. **Previous line** — 300 px wide, 2-line clamp, 14/20, muted, 0.45 opacity when present.
   It is `aria-hidden`: it is visual context, not a second announcement.
2. **Now** — speaker label + caption. The caption is 22/30, weight 500, maximum 3 lines,
   balanced wrapping, 312 px wide. The speaker label is purple for ChatGPT and warm paper
   for You.
3. **Hidden state** — eye-off icon, "Transcript hidden", "The call is still live.", and a
   `Show transcript` text button. This is a real state, not a blank screen.

The `now` block is `aria-live="polite"` and `aria-atomic="true"` so a new turn is announced
as one unit. The whole live area is the conversation's front door: tapping it opens the
thread, but taps on buttons inside it are ignored. Opening the thread does not focus the
composer.

While composing, the previous line is hidden and `now` moves up (`margin-top: 8px`) to give
the field room. This is why the live caption can lose a line without the controls moving.

Caption swap: 400 ms, opacity 0 → 1 and translateY 5 px → 0, only when the turn changes and
motion is allowed.

---

## 7. Composer: entry, growth, and rules

### 7.1 Entry

`More` opens the **Call options** sheet. The first row is **Type a message** (pencil icon,
chevron). Tapping it is a mode change, not a normal sheet dismissal:

- the sheet is hidden immediately (no 260 ms exit, no scrim fade);
- the background is un-inerted;
- the composer is made visible and focused inside the same tap, so the phone keyboard opens;
- the composer runs its 220 ms translateY(6 → 0) + fade entrance;
- the ghost starts rotating.

The sheet never remains visually behind a focused field. This is deliberate: a fading panel
behind a live caret reads as a bug and delays the keyboard.

### 7.2 Geometry

- Field: 312 max width, 16 radius, `#202329`, 11 px top/bottom padding, 14 px horizontal.
- Input: `min-height: 22`, `max-height: 88`, line-height 22, font 16/22, transparent,
  no border, caret `#9A8BF2`.
- Field heights: **44 / 66 / 88 / 110** for 1 / 2 / 3 / 4 lines. The input scrollport is
  **22 / 44 / 66 / 88**.
- The input grows with `scrollHeight`, clamped to 88; beyond that it scrolls internally
  (`overflow-y: auto`, 4 px scrollbar, `overscroll-behavior: contain`).
- The vertical padding lives on the field, not the input, so long drafts clip on a line
  boundary instead of mid-glyph.
- The composer is inside `.replybar`, whose bottom margin is 12 to the controls.
- The form is `hidden` until opened; the open state is a class on the form.

### 7.3 Typing and sending

| Gesture | Result |
|---|---|
| Tap field | Focus; keyboard opens |
| Enter | Send (prevent default) |
| Shift + Enter | Newline |
| IME action "send" | Send (arrives as Enter; `enterkeyhint="send"`) |
| IME composition | Ignored (`isComposing` / keyCode 229) |
| Empty or whitespace | No-op |
| Over 400 chars | `maxlength=400`; send also trims and slices to 400 |

The input is a real `<textarea>`, not a contenteditable. This preserves native selection,
spellcheck, autocapitalisation (`sentences`), autocomplete off, and the platform's multiline
keyboard behaviour. `aria-label="Message ChatGPT"`; the ghost is `aria-hidden`.

There is no attach, mic, or send button on the stage. The IME action is the send affordance.

### 7.4 Focus, blur, close, draft

- `openComposer()` focuses inside the tap. This is required for the mobile keyboard.
- `blur` waits 140 ms, then closes **only if the field is empty** and focus has not returned.
  A non-empty draft keeps the field open across focus changes.
- `closeComposer()` stops the ghost, cancels any dissolve, blurs, removes the open class and
  `is-composing`, and after 220 ms hides the form and clears its inline height/overflow.
  The draft value is **not** cleared.
- Opening the conversation closes the composer instantly (draft preserved).
- Ending the call or replaying closes it instantly and clears it on replay.
- The draft survives closing and reopening the composer within the session.

### 7.5 Ghost

Four lines rotate every **4200 ms**, fading out for 200 ms, swapping the text, fading in:

```
Message ChatGPT
Ask about the canary
Tell it to hold
Type a reply
```

The ghost is hidden when the field has any value, during a dissolve, when the composer is
closed, when the tab is hidden, and under reduced motion. It is not a `<label>`; it is
decorative guidance with `aria-hidden="true"`.

### 7.6 Composer state machine

```
closed ──More→Type a message──▶ opening ──(next frame + focus)──▶ open
   ▲                                                               │
   │                                           type / grow / scroll │
   │                                                               ▼
   └──blur(empty, 140ms) / end / replay / thread──────────────── open
                                                                   │
                                                          Enter / IME send
                                                                   ▼
                                                              vanishing
                                                     ┌─────────────┴─────────────┐
                                             typing / close /          sweep reaches 0
                                             reduce motion                    │
                                                     ▼                        ▼
                                                  open                 settling (200ms)
                                                                              │
                                                                              ▼
                                                                       open, 1 line
```

While vanishing: the input value is already empty, but the field keeps its pre-send height,
the ghost is hidden, the caret is transparent, and focus stays in the field. Typing cancels
the dissolve immediately and keeps the first keystroke.

---

## 8. Send flow

`submitComposer()` is intentionally small and ordered:

1. Ignore if the call has ended.
2. Read the raw value; ignore if it is only whitespace.
3. `sendMessage(text)` — commit first (thread, live caption, presence, `state.lastText`).
4. `startVanish(text)` — if it returns false (reduced motion, no canvas context, no text
   pixels), clear the field, resize, sync the ghost, and re-focus.
5. The dissolve runs as a visual exit; it never delays the message.

`sendMessage()` in the prototype also schedules a scripted AI turn. In the app that block is
replaced by the real call event stream; keep only the optimistic human turn, the live caption
update, and the thread insertion. See section 13.

The live caption shows the sent text immediately, the previous line becomes whatever was
showing before, and the conversation (if open) scrolls to the new message.

---

## 9. The send dissolve

This is the 21st.dev `PlaceholdersAndVanishInput` idea adapted to a multiline, internally
scrolled field. The text is lifted onto a canvas, sampled into grains, and erased right to
left by a moving front. It is a visual exit, not a loading state.

### 9.1 Build (synchronous, before the first frame)

Read from the live field:

- computed font (`fontStyle`, `fontVariant`, `fontWeight`, `fontSize`, `fontFamily`), colour,
  line-height;
- `stageField.clientWidth/Height` for the canvas box;
- `stageInput.clientWidth`, `clientHeight`, `offsetLeft`, `offsetTop`, `scrollTop`;
- content box: `left = offsetLeft + 14`, `top = offsetTop`, `width = clientWidth − 28`,
  `height = clientHeight`.

Canvas:

- `DPR = min(devicePixelRatio, 3)`;
- backing store `field size × DPR`, CSS size exactly the field size;
- append inside the field, above the input (`z-index: 2`), `aria-hidden`, pointer-events none.

Line measurement (the browser must supply the breaks, not a hand-rolled wrapper):

- create a hidden mirror `<div>` at `left: -9999px`, `visibility: hidden`, with the same width,
  font, letter-spacing, word-spacing, direction, text-transform, text-align, and line-height;
- `white-space: pre-wrap; overflow-wrap: break-word; word-break: normal;`
- put the text in a single text node;
- for each character (surrogate pairs as one unit), measure `Range.getBoundingClientRect()`;
  a vertical jump > 0.5 px starts a new line; explicit `\n` forces a break;
- remove the mirror before returning.

Draw:

- baseline for line 0: `contentTop − scrollTop + (lineHeight − ascent − descent) / 2 + ascent`,
  using the font's box ascent/descent (fall back to actual bounds, then 15 / 4);
- clip to the input scrollport (`contentLeft`, `contentTop`, `contentWidth`, `contentHeight`)
  so a line scrolled above the viewport cannot leak into the field's top padding;
- draw each measured line at `contentLeft`, advancing by `lineHeight`.

Sampling:

- read the canvas pixels once;
- `step = max(1, round(DPR))` device px → one sample per CSS px;
- ignore alpha ≤ 10;
- create `{x: cssX, y: cssY, r: 1, color: rgba(..., alpha)}` for every hit;
- track `maxX` (the rightmost grain).

If there are no grains, return false and let the caller clear normally.

### 9.2 Sweep

State: `pos = maxX`, `last = 0`.

Each frame:

```
delta = last ? min(48, now - last) : 16.67
scale = delta / 16.67
pos -= 8 * scale
```

- Clear the right strip: `clearRect(pos, 0, width - pos, height)` in CSS coordinates.
- For each grain:
  - if `x < pos`: it has not been reached; keep it in the source array (it is already
    painted and will be reached later);
  - if `r <= 0.06`: drop;
  - otherwise jitter `x ± 1 * scale`, `y ± 1 * scale`, shrink
    `r -= (0.045 + random * 0.045) * scale`; drop if `r <= 0.06`;
  - draw only grains with `x > pos` as `fillRect(x, y, r, r)` with their sampled colour.
- Continue while `pos > 0`; otherwise finish.

The sweep is time-based: **480 CSS px/s**, about **590 ms for a 284 px line**, regardless of
frame rate. The `48 ms` delta clamp prevents a background-tab jump from skipping the whole
field.

### 9.3 Finish and settle

`finishVanish()`:

- stop the loop, remove the canvas and `is-vanishing`;
- if the composer is still open: add `is-settling`, set the input height to `22px` and
  `overflow: hidden` directly (do **not** re-measure with `height:auto`, or the transition is
  lost), sync the ghost, and remove `is-settling` after 240 ms.

The height transition is 200 ms ease-out. On a 2-line draft the sequence is: 66 held during
the sweep, then a visible 66 → 44 settle as the dust clears. The controls stay at 528
throughout; the live region grows back by the same amount.

### 9.4 Cancel and fallback

| Trigger | Result |
|---|---|
| Typing during the sweep | Cancel at once; keep the typed character; resize to the draft |
| Close composer | Cancel; remove canvas/RAF; hide the composer |
| End / replay / open thread | Cancel via close, then tear down |
| Reduced motion turns on mid-flight | Cancel immediately |
| Reduced motion at send | No canvas at all; clear, resize, refocus |
| No 2D context / no grains | `startVanish` returns false; caller clears normally |

Cancel removes the canvas and both classes, cancels the RAF, and re-runs the composer sizing
and ghost sync if the composer is open.

### 9.5 Cost and limits

- Measured on desktop Chrome: a 400-character draft builds in ~14 ms; the sweep runs at
  ~60 fps on a 312 px field.
- Grain count scales with visible text area (roughly one per CSS px of glyph coverage).
  Four lines is the worst case and is bounded by the 88 px scrollport.
- DPR is capped at 3 for the dissolve (text crispness) and 2.5 for the ambient waves
  (fill cost). Do not raise these without measuring on a mid-range phone.
- The mirror is removed before the first frame; no layout node survives the send.

### 9.6 Pseudocode

```js
function submit(text) {
  commitMessage(text);                 // thread + live line, first
  if (!startVanish(text)) {            // false = reduced / unsupported
    clearField(); resizeField(); showGhost(); focusField();
  }
}

function startVanish(text) {
  if (reduceMotion) return false;
  const m = measureField();
  const lines = measureLinesWithMirror(text, m);   // browser-exact wraps
  paintLinesOnCanvas(lines, m);                    // clipped to scrollport
  const grains = samplePixels(canvas);             // 1 per CSS px
  if (!grains.length) return false;

  clearInputValue(); hideGhost(); appendCanvas();
  keepFieldHeight(); focusField();                 // keyboard stays

  let pos = maxRight(grains);
  onFrame((dt) => {
    pos -= 8 * (dt / 16.67);
    eraseRightOf(pos);
    updateAndDrawGrains(pos);
    if (pos <= 0) finishAndSettle();
  });
  return true;
}
```

---

## 10. More and Audio sheets

Both are bottom sheets inside the screen, `role="dialog"`, `aria-modal="true"`, labelled by
their title.

- Scrim `rgba(0,0,0,.62)` fades in over 200 ms; panel translates 14 → 0 over 220 ms.
- Panel: full width, min-height 312, top radius 28, `#191B20`, 10/24/24 padding, handle 32×4.
- Background `.app` becomes `inert` while a sheet or the thread is open; the sheet/thread
  lives outside `.app` so it stays interactive.
- Focus moves to the first row after 80 ms; Tab is trapped; Esc closes; the scrim closes;
  focus returns to the opener (More or Audio).
- More rows: **Type a message**, **Audio output** (current value + chevron), **Repeat last
  line**, **Transcript** (switch, `aria-checked`).
- Audio sheet: radio group, three rows —
  `Phone earpiece / Private`, `Speaker / Hands-free`, `Pixel Buds Pro / Bluetooth · Connected`.
  The Bluetooth name is demo data; use the real device name in the app.
- Route selection updates the control glyph, the button's `aria-label`
  (`Audio output: …`), and the More row's short value (`Earpiece`, `Speaker`, `Bluetooth`).
  Arrow Up/Down moves the radio selection; selecting closes the sheet after 160 ms.
- Audio opened from More replaces the panel instead of stacking two dialogs; focus returns
  to More when it closes.

---

## 11. Conversation sheet

The conversation is the call's memory. It slides in below the header, from `top: 254` to the
bottom, over the stage. The header, timer, and both voice fields stay visible above it.

- Panel: `#191B20`, top radius 24, shadow up, 260 ms entrance.
- Head: "Conversation", message count, 44 px close button.
- Messages: speaker dot (purple AI / warm human), name, time, 15/22 text. The live message
  gets a dot and pure white text. Numeric times use tabular figures. The prototype's clock
  is fake; the app uses real timestamps.
- List: 24 px side padding, 24 px bottom; hidden scrollbars; 14 px vertical message padding
  with a 6% white divider; first message has no divider.
- The list keeps 80 messages and drops the oldest.
- Auto-scroll: if the user is within 28 px of the bottom, new messages stick; otherwise the
  current scroll anchor is preserved and a **Jump to live** pill appears at the bottom
  (34 px, 84 px from the bottom, above the composer).
- The sheet has its own single-line composer (44 px input + 44 px send disc) with a disabled
  send until there is text. It is a secondary path; the stage composer is the primary one.
  Sending from either path updates the same thread.
- Opening the conversation closes the stage composer instantly; closing returns focus to the
  live region.

---

## 12. End and replay

- **End** (red 56 px disc) immediately stops the call UI: timers clear, composer/thread/sheets
  close, the live region is hidden, the replybar is removed, the controls become invisible
  but keep their layout, and the voice fields freeze.
- The ended panel sits at `top: 228`, `bottom: 196`, centred: "Call ended", "Duration m:ss",
  and a **Replay** pill (48 px high, min 132 wide, `#5B4BC4`).
- The duration is written into the visible status region *after* it is shown so screen
  readers announce the result rather than a mutation inside a hidden tree. Focus moves to
  Replay.
- **Replay** resets the demo: clock 84 s, seeded thread, empty fields, ghost line 0, first
  turn, focus back on Mute. In the app, Replay should start a new call session or return to
  the previous screen; do not ship a fake 84 s reset.

---

## 13. State model and integration contract

### 13.1 View state

| State | Type | Notes |
|---|---|---|
| `ended` | bool | Blocks composer, sends, threads, sheets |
| `speaker` | `'ai' \| 'human' \| null` | Drives presence, caption colour, waves |
| `muted` | bool | Mutes the AI; presence dot amber |
| `route` | `earpiece \| speaker \| bluetooth` | Audio output |
| `hidden` | bool | Transcript hidden state |
| `seconds` | int | Call duration; app derives from start time |
| `lastText` | string | Previous line |
| `turn` | int | Demo turn cursor; app replaces with event-driven turns |
| `reduced` | bool | Mirrors the OS motion preference |
| `composerOpen` | bool | Local UI |
| `vanishRunning` | bool | Local UI, blocks ghost, allows cancel |
| `threadOpen` | bool | Local UI |

### 13.2 Events the screen needs from the app

The screen should be a pure projection of these inputs; it must not own a scripted dialogue.

- **Call started** — agent name/identity, start time, initial transcript history.
- **Speaker changed** — `ai | human | null`; updates presence, caption colour, wave target.
- **Partial transcript** — optional; if used, update the live caption without committing a
  thread line. Agree the throttling before implementation.
- **Final turn** — `{ id, speaker, text, timestamp }`; append once, de-duplicate by id.
- **Muted changed** — from the app/audio layer; reflect, do not assume local-only.
- **Route changed** — from the audio layer; reflect the real device name.
- **Call ended** — reason, final duration; show the ended panel.
- **Error / reconnecting** — not designed in this prototype. A real app needs a banner or a
  presence variant; do not silently reuse "Connected". Flag this as a design gap.

### 13.3 Intents the screen emits

- `sendText(text)` — optimistic local echo, then server/transport ack.
- `setMuted(bool)`.
- `setRoute(routeId)`.
- `repeatLastLine()`.
- `endCall()`.
- `openConversation()` / `closeConversation()` — purely local in the prototype.

The prototype's `sendMessage()` must be split: keep the UI commit (thread + live line), drop
the `later(1700) / later(620) / startTurn()` loop, and let the real event stream drive the
next turn. If the transport fails, the app needs a failed-message state; this prototype does
not include one.

### 13.4 Prototype module map (for porting)

| Function | Responsibility |
|---|---|
| `sizeComposer()` | Measure scrollHeight, clamp to 88, set height/overflow |
| `syncGhost()` | Show ghost only when empty, open, and not vanishing |
| `rotateGhost()` / `startGhost()` / `stopGhost()` | 4.2 s placeholder rotation |
| `measureVanishLines()` | Hidden-mirror line boxes, explicit breaks, surrogate pairs |
| `startVanish()` / `cancelVanish()` / `finishVanish()` | Canvas build, sweep, cancel, settle |
| `openComposer()` / `closeComposer()` | Focus, class transitions, timers, draft preservation |
| `submitComposer()` | Commit then dissolve, with fallback |
| `dismissSheetForComposer()` | Instant sheet-to-field hand-off, inert reset, focus |
| `sendMessage()` | Optimistic human turn + demo AI scheduling (drop the latter) |
| `paintPresence()` | Dot colour + label + thread refresh |
| `paintTurn()` / `startTurn()` | Demo turn loop (drop in app) |
| `recordTurn()` / `renderThread()` | Thread append, 80-message cap, pinned scroll |
| `openThread()` / `closeThread()` | Panel, inert, focus trap, focus return |
| `openSheet()` / `closeSheet()` / `openAudioSheet()` | Sheets, focus, inert, focus return |
| `endCall()` / `replay()` | Teardown, ended panel, demo reset |
| `freezeStill()` / `resumeMotion()` | Reduced-motion still frame / resume |
| `VoiceField` | Dither rendering and envelope |

---

## 14. Accessibility

- **Focus order** on the stage: live region (tabindex −1), composer (when open), Mute, Audio,
  More, End. The live region is a tap target but not a tab stop in normal use; keep its
  `tabindex="-1"` for programmatic focus only.
- **Focus trapping:** sheets and the conversation trap Tab; `inert` on `.app` prevents
  background focus. On close, focus returns to the opener.
- **Live announcements:** the `now` block is `aria-live="polite"`, `aria-atomic="true"`. The
  previous line is `aria-hidden`. Do not add a second live region for the same turn.
- **Call ended:** the ended copy is `role="status"`; title and duration are set after the
  panel is visible. Focus moves to Replay.
- **Composer:** native textarea semantics, `aria-label`, `enterkeyhint="send"`, 16 px text
  (prevents iOS zoom), visible focus ring on the field, caret visible. The ghost and canvas
  are `aria-hidden`.
- **Keyboard:** Enter sends, Shift+Enter newline, Esc closes sheet/thread, Tab is trapped
  inside overlays, Up/Down changes the audio route.
- **Reduced motion:** no ambient waves, no dissolve, no ghost rotation, no caption swap, no
  sheet/thread slide. The composer opens and closes instantly. All functions still work.
- **Targets:** 56 px discs, 44 px minimum for close/show/send, 48 px for Replay, 56 px sheet
  rows. The 34 px Jump to live pill has an 8 px hit-area expansion.
- **Contrast:** the pairs in section 3.1 clear 4.5:1. Focus rings are 2 px `#9A8BF2` with a
  2–3 px offset.
- **Screen-reader caveat:** a dissolve is invisible to assistive tech; the message is already
  in the thread, so no announcement is needed for the animation itself.

---

## 15. Responsive and device behaviour

The prototype's 360 × 800 artboard and `--s` scaling are **preview-only**. The app uses real
window size and safe areas.

- Baseline layout: 24 dp side padding; 312 dp content; 4 × 72 dp controls with 8 dp gaps.
- Safe areas: the stage extends edge to edge behind the status bar and home indicator; the
  app's own header/status region stays OS-owned. Do not draw a fake 9:41 bar or a fake home
  indicator in the app.
- The top and bottom voice fields are full-bleed and fixed 180 dp tall. They must not be
  squeezed by the keyboard; let the live region and composer absorb the inset.
- **Keyboard (open decision):** the prototype never simulates the soft keyboard. On device,
  decide between `adjustResize` / `imePadding` (controls ride above the keyboard) and an
  overlay composer. The design intent is that the four controls remain reachable; verify on
  a small phone before shipping. Do not let the keyboard cover End.
- **Large text:** the composer clamp is in lines, not a fixed text size. At 1.3× and 2× the
  4-line cap grows in dp; the live caption's 3-line clamp and the 118/90 min-heights need a
  device check. If the layout cannot hold both, prefer keeping the controls and the field
  usable over showing three caption lines.
- **Landscape:** the app is portrait-first. If landscape is supported, the stage needs its
  own rule; do not simply rotate the 360 × 800 layout.
- **Haptics (optional):** a light impact on send and on End is consistent with the physical
  feel, but it is not part of the approved prototype; add only with product sign-off.

---

## 16. Native implementation notes

### Android (Compose)

- **Stage:** `Column` with the header, flexible live area, replybar, and a fixed controls row.
  Anchor the controls with bottom padding equal to the wave height + 16 dp; let the live area
  take the remaining height (`weight(1f)`), exactly like the web flex column.
- **Composer:** `BasicTextField` / `TextField` with 16 sp text, 22 dp line height, `maxLines`
  or a measured height clamp at 4 lines, internal vertical scroll, 16 dp field radius, 11 dp
  vertical padding on the container. Expose `imeAction = Send`; handle hardware Shift+Enter.
- **Line boxes:** `TextLayoutResult` gives `lineCount`, `getLineStart`, `getLineTop`, and
  `getLineBottom`; use those instead of the hidden mirror. Apply the same scroll offset as
  the field.
- **Dissolve:** `Canvas` over the field; `drawText` with the same `TextStyle`/`TextMeasurer`
  after clipping to the field; sample pixels from an offscreen `ImageBitmap`; animate with
  `withFrameNanos`, 8 dp per 16.67 ms, same grain rules; remove the overlay and animate the
  field height to 44 dp over 200 ms.
- **Waves:** 120 × 60 alpha buffer + Bayer; `ImageBitmap` scaled 3× with
  `FilterQuality.None`; one `withFrameNanos` loop, paused with the lifecycle.
- **A11y:** `semantics { liveRegion = LiveRegionMode.Polite }` on the live turn; content
  descriptions on the four controls; `focusRequester` for replay/sheet open; clear semantics
  on the decorative canvases.
- **Motion:** respect the system animator duration scale / remove-animations setting; keep a
  manual override only if the product asks for one.

### iOS (SwiftUI / UIKit)

- **Stage:** `VStack` with a flexible live area and a fixed controls row; use
  `safeAreaInset` or bottom padding for the wave band. Keep the controls out of the keyboard
  inset or explicitly above it — decide with section 15.
- **Composer:** wrap `UITextView` in `UIViewRepresentable` for line boxes and scrolling.
  `textContainerInset` 11/14, `lineFragmentPadding = 0`, 16 pt text, 22 pt line height,
  `returnKeyType = .send`, `submitLabel(.send)`. Intercept Return; allow Shift+Return.
- **Line boxes:** TextKit 1 `layoutManager.enumerateLineFragments` or TextKit 2
  `NSTextLayoutManager`; convert to the field's coordinate space and subtract `contentOffset`.
- **Dissolve:** render the visible lines into a `CGContext`/`UIImage`, sample the alpha
  channel into grains, drive with `CADisplayLink` (or `TimelineView(.animation)`), and mask
  the right strip each frame. Animate the field height with the same 200 ms ease-out.
- **Waves:** a 120 × 60 `CGImage` with `interpolation(.none)`; `CADisplayLink` for the
  envelope; pause on `scenePhase` changes.
- **A11y:** `accessibilityLabel`/`accessibilityValue` on the controls; `.accessibilityAddTraits`
  for the composer; post `.announcement` for call ended; `UIAccessibility.isReduceMotionEnabled`
  gates the dissolve and waves.
- **Keyboard:** `keyboardLayoutGuide` / `ignoresSafeArea(.keyboard)` deliberately, not by
  accident. Verify the End control stays reachable with the keyboard up.

---

## 17. Copy inventory

### Shipped UI copy (keep)

- `ChatGPT` (agent name; real app uses the agent's name)
- `AI speaking`, `AI muted`, `Listening to you`, `Connected`, `Call ended`
- `Message ChatGPT` (ghost and aria-label; real app interpolates the agent name)
- `Ask about the canary`, `Tell it to hold`, `Type a reply` (ghost rotation; can be
  localised/replaced, keep the same length and tone)
- `Mute` / `Unmute`, `Audio`, `More`, `End`
- `Call options`, `Type a message`, `Audio output`, `Repeat last line`, `Transcript`
- `Phone earpiece / Private`, `Speaker / Hands-free`, `Bluetooth · Connected`
- `Conversation`, `Jump to live`, `Send message`
- `Transcript hidden`, `The call is still live.`, `Show transcript`
- `Call ended`, `Duration`, `Replay`

### Demo-only copy (do not ship)

- The five scripted turns and the six seeded thread messages.
- `Pixel Buds Pro` as a hard-coded device name.
- The fake `9:41` status bar and `1:24` initial duration.
- The 84-second replay reset.

---

## 18. Acceptance checklist

### Functional

- [ ] Stage is clean on entry: no composer, no attach/mic/send disc.
- [ ] More → Type a message opens the field, keyboard up, sheet gone instantly.
- [ ] Field heights are 44 / 66 / 88 / 110; internal scroll starts at line 5; max draft 400.
- [ ] Enter sends; Shift+Enter inserts a newline; IME send sends; composition is ignored.
- [ ] Sending appends to the thread and updates the live caption before any animation.
- [ ] The field holds its pre-send height through the sweep, then settles to 44 over 200 ms.
- [ ] Typing during the sweep cancels it and keeps the keystroke.
- [ ] Closing, blurring empty, opening the thread, ending, and replaying all clean up the
      canvas and RAF.
- [ ] A non-empty draft survives blur, close, reopen, and opening the thread.
- [ ] Reduced motion: no canvas, instant clear, no ghost rotation, still wave frame.
- [ ] Controls never move when the composer grows or the caption wraps.
- [ ] 400-char draft + long caption: field clears the caption by ≥ 12, no document scroll.
- [ ] End closes everything, shows duration, focuses Replay; Replay resets cleanly.
- [ ] Conversation auto-scrolls when pinned, shows Jump to live when not, caps at 80.

### Visual

- [ ] Palette and contrast match section 3; no gradients, glows, or glass.
- [ ] Dither cells are 3 px hard-edged; no smoothing or blur.
- [ ] AI field hangs from the top; human field rises from the bottom; only the speaker moves.
- [ ] Composer radius 16, field 312 wide, 14 px text inset.
- [ ] Focus ring is purple, 1 px inset on the field; no default blue outline.
- [ ] End is the only red element; the End label is `#C98282`.
- [ ] Sheets have a 28 top radius; the thread has 24; handles are 32 × 4.
- [ ] At 360 × 800 everything fits without document scroll.

### Motion

- [ ] Composer entrance 220/180 ms; exit 220 ms; settle 200 ms.
- [ ] Ghost rotates every 4.2 s with a 200 ms fade.
- [ ] Sweep is ~480 px/s (≈ 590 ms for a full line), never a fixed short duration.
- [ ] Grains jitter ±1 px, shrink, and thin out; the cloud is visible at the front.
- [ ] Reduced motion collapses all of the above without breaking layout.

### Accessibility

- [ ] Live turn is a polite, atomic live region; previous line is hidden from AT.
- [ ] Sheets/thread trap focus, background is inert, focus returns to the opener.
- [ ] Call ended is announced once with the final duration; Replay is focused.
- [ ] All text pairs ≥ 4.5:1; touch targets ≥ 44/48; focus visible on every control.
- [ ] 16 px composer text; no iOS zoom on focus.

### Performance

- [ ] 400-char send build ≤ ~30 ms on the target mid-range device (prototype: ~14 ms
      desktop); no dropped frames during the sweep.
- [ ] No canvas, RAF, mirror, or timer leaks after 20 send/cancel/close cycles.
- [ ] Backgrounding the app pauses the waves and the clock; returning resumes without a jump.

### Evidence from the prototype

| Check | Result |
|---|---|
| Idle | composer hidden, replybar 0, live 262, controls 528 screen-local (548 in the 400 × 840 page) |
| Open | field 44, live 218, gap 12, focus true, `enterkeyhint=send` |
| 1/2/3/4 lines | 44/66/88/110; scrollport 22/44/66/88 |
| Long draft | scrollHeight 132, scrollTop 44, field 110, no page scroll |
| Send (2 lines) | message in thread, field holds 66, canvas 624×132 at DPR 2 |
| Mid-sweep | dust at the front, field 66/110, ghost 0 |
| Settled | field 44, ghost 1, focus retained |
| Typing mid-sweep | vanish false, value `x`, canvas gone |
| Close mid-sweep | composer closed, no canvas, no RAF |
| Reduced motion | no canvas, field 44 immediately |
| Long unbroken token | canvas wraps exactly like the field, all visible lines dissolve |
| 360×800 max draft | field 110 layout (≈100 measured), gap 12 (≈11), controls 517 measured |

---

## 19. Do not copy from the prototype

- The scripted `TURNS` loop, `THREAD_SEED`, and `startTurn` timers.
- The fake 84 s clock, the 9:41 status bar, the home-indicator bar, and the page shell.
- The Google Fonts `<link>` (bundle Inter; the app already has it).
- The `window.agentcallOngoing` QA hook.
- The `impeccable-live` `<script>` at the end of the HTML (a live-reload overlay).
- The browser's `prefers-reduced-motion` CSS blanket as the only reduced-motion strategy;
  the app must also respect the OS setting natively.
- The `--s` artboard scaling: it is a preview convenience, not a layout rule.

---

## 20. Open decisions for the real app

1. **Keyboard strategy.** Resize the layout and keep the controls above the keyboard, or use
   an overlay composer? The prototype doesn't simulate the keyboard; this needs a device
   decision before implementation.
2. **Partial transcripts.** Does the live caption update word-by-word, or only on final
   turns? If partial, define throttling and whether the thread shows partials.
3. **Failed send.** The prototype has no failure state. Define a retry affordance (inline
   warning, tap to retry) before shipping on a real network.
4. **Reconnect / degraded audio.** No design exists. The presence system has no
   "Reconnecting" state on this screen; add one deliberately if the call stack can enter it.
5. **Composer after send.** Current behaviour keeps the keyboard up and the field open at one
   line. Confirm this is wanted on a real call, or dismiss the keyboard after send.
6. **Draft persistence scope.** Current: session-only, cleared on Replay. Should a draft
   survive leaving and re-entering the call screen? If yes, define the storage and expiry.
7. **Real audio envelope.** Replace the synthetic `speechLevel` with the actual TTS/WebRTC
   level; define attack/release and silence thresholds so the field doesn't twitch on noise.
8. **Wave cost on low-end devices.** The 3 px cell grid and 2.5 DPR cap are the prototype's
   budget. Measure on the oldest supported phone; if needed, drop to 4 px cells before
   reducing the motion.
9. **Conversation timestamps.** The prototype invents a 9:40 start. The app should use the
   real call start and locale-aware time formatting.
10. **Haptics and sound.** Not specified. Decide whether send/end get a light impact and
    whether the dissolve has any audio.

---

## Appendix A — token quick reference

```css
--screen-bg: #0e0f12;
--surface: #191b20;
--surface-raised: #202329;
--outline: rgba(255,255,255,0.12);
--on-surface: #f1f2f4;
--muted: #9ca1ab;
--ai: #7967de;
--ai-bright: #9a8bf2;
--human: #efe9df;
--decline: #ff6b6b;
--decline-icon: #16181c;
--amber: #e8b04b;
--ease-out: cubic-bezier(0.22, 1, 0.36, 1);
```

## Appendix B — measured geometry (360 × 800 artboard)

Screen-local CSS px, measured from the live prototype at 400 × 840 (the shell is centred with
20 px above and below). Frame px are the 360 × 800 shell captured at DPR 2, i.e. local × 2 —
the size of the frames in `.ui-verify/redesign/qa/`.

| Element | Frame px (DPR 2) | Screen-local |
|---|---|---|
| Shell | 720 × 1600 | 360 × 800 |
| Top voice field | 720 × 360 | 360 × 180, at 26 |
| Call header | 416 → 508 | 208 → 254 |
| Idle live height | 524 | 262 |
| Composing live, 1 line | 436 | 218 |
| Composing live, 4 lines | 304 | 152 |
| Field, 1/2/3/4 lines | 88 / 132 / 176 / 220 | 44 / 66 / 88 / 110 |
| Field bottom, 1 line | 1032 | 516 |
| Controls top | 1056 | 528 |
| Bottom wave top | 1240 | 620 |
| Thread top | 508 | 254 |
| Ended box top / bottom | 456 / 1208 | 228 / 604 |

## Appendix C — prototype function index

| Area | Functions |
|---|---|
| Waves | `VoiceField.configure`, `.step`, `.render`, `speechLevel`, `frame`, `freezeStill`, `resumeMotion` |
| Header | `paintPresence`, `formatDuration` |
| Live | `paintTurn`, `sendMessage`, `repeatLast`, `startTurn`, `recordTurn`, `renderThread` |
| Composer | `sizeComposer`, `syncGhost`, `rotateGhost`, `startGhost`, `stopGhost`, `openComposer`, `closeComposer`, `submitComposer`, `dismissSheetForComposer` |
| Vanish | `measureVanishLines`, `startVanish`, `cancelVanish`, `finishVanish`, `removeVanishCanvas` |
| Overlays | `openSheet`, `closeSheet`, `openAudioSheet`, `openThread`, `closeThread`, `syncInert`, `trapFocus` |
| Lifecycle | `endCall`, `replay`, `handleMotionChange` |
| Hook | `window.agentcallOngoing` (QA only) |

---

End of specification. The HTML and the frames in `.ui-verify/redesign/qa/` are the visual
oracle; this document is the behavior and implementation contract. Do not start app work
until this document is confirmed.
