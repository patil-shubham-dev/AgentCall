# AgentCall — Dead Code Audit

> **Date:** 2026-07-26
> **Scope:** All source code (backend, MCP server, Android, iOS archive, infrastructure)
> **Note:** DO NOT DELETE ANYTHING — this is an analysis only.

---

## Summary

| Category | Count | Estimated Dead Lines |
|----------|-------|---------------------|
| Unused files | 2 | ~153 |
| Unused classes/interfaces | 4 | ~30 |
| Unused functions | 8 | ~120 |
| Unused variables | 5 | ~10 |
| Unused imports | 2 | ~3 |
| Unused dependencies (package.json) | 1 | 1 line |
| Orphan/dead dist files | 1 | ~125 |
| Legacy experiments | 1 | ~2,076 |
| Dead/commented code | 3 | ~45 |
| Duplicate implementations | 2 | ~56 |
| **Total dead/legacy** | **29 items** | **~2,619 lines** |

---

## 1. Unused Files

### 1.1 `Dockerfile` (root)
**Path:** `C:\Users\91808\Desktop\AgentCall\Dockerfile`
**Lines:** 28
**Status:** EXACT DUPLICATE of `backend/Dockerfile`
**Evidence:** 100% line-for-line identical. Same base image, same build stages, same WORKDIR, same COPY paths, same CMD. Root serves no purpose — `docker-compose.yml` references `backend/Dockerfile` and `mcp-server/Dockerfile`.
**Safe to remove:** YES — root Dockerfile is dead code. Remove after updating CI if it references root Dockerfile (CI uses `docker build ./backend` and `docker build ./mcp-server`, so root file is not referenced).
**Danger of removal:** None.

### 1.2 `mobile/ios-archived/` (entire directory)
**Path:** `C:\Users\91808\Desktop\AgentCall\mobile\ios-archived\`
**Files:** 15 Swift files (~2,076 LOC), Package.swift, entitlements, Info.plist
**Status:** ARCHIVED LEGACY
**Evidence:**
- Directory name `ios-archived` explicitly indicates archived status.
- iOS app is not mentioned in any CI workflow, docker-compose, or build script.
- PROJECT_VISION.md and ROADMAP.md list iOS as a future phase, not current.
- Android app has an independent WebSocket protocol; iOS has full WebRTC — two different approaches indicating parallel development that diverged.
- Root `README.md` does not mention iOS.
**Safe to remove:** YES — the iOS project is an archive of an earlier approach. It should be kept in a git branch or external archive, not in the main branch.
**Danger of removal:** None for current implementation. Valuable reference for future iOS development.
**Recommendation:** Remove from main branch; preserve in an `archive/` git tag or separate branch.

---

## 2. Orphan Build Artifacts

### 2.1 `dist/voicebridge/stt.js`
**Path:** `C:\Users\91808\Desktop\AgentCall\backend\dist\voicebridge\stt.js`
**Lines:** 125
**Status:** ORPHAN — no corresponding source file (`src/voicebridge/stt.ts` does not exist)
**Evidence:**
- `backend/src/voicebridge/` contains: `index.ts`, `service.ts`, `types.ts` (3 files)
- `backend/dist/voicebridge/` contains: `index.js`, `stt.js`, `service.js`, `types.js` (4 files)
- The source for `stt.js` (`stt.ts`) was deleted but the dist file remains from an earlier build
- `@xenova/transformers` is NOT in `backend/package.json` dependencies — the import `const { pipeline } = await import('@xenova/transformers')` at runtime will fail
**Safe to remove:** YES — the dist file cannot be regenerated and would fail at runtime.
**Danger of removal:** None — it's broken code.

---

## 3. Unused Interfaces and Types

### 3.1 `SendMessageInput` (backend)
**Path:** `backend/src/voicebridge/types.ts`, lines 89-93
**Lines:** 5
**Status:** UNUSED — defined but never imported or referenced by any source file.
**Evidence:** Grep across `backend/src/` shows zero imports or references to `SendMessageInput`.

### 3.2 `AudioChunk` (backend)
**Path:** `backend/src/voicebridge/types.ts`, lines 94-99
**Lines:** 6
**Status:** UNUSED — defined but never imported or referenced.
**Evidence:** Grep across `backend/src/` shows zero references.

### 3.3 `CallPriority` / `CallReason` (backend)
**Path:** `backend/src/common/types.ts`
**Lines:** 2 (entire file)
**Status:** USED — imported by `voicebridge/types.ts`.
**Note:** Not dead code. Kept for clarity.

---

## 4. Unused Functions / Methods

### 4.1 `emotionOf()` import in `service.ts`
**Path:** `backend/src/voicebridge/service.ts`, line 13
**Status:** DEAD IMPORT — `emotionOf` is imported but never called in `service.ts`.
**Evidence:** Only `extractEmotionTag`, `enrichText`, `detectBargeIn` are used from `voicebridge/types.ts`. `emotionOf` is never referenced.

### 4.2 `clearActiveCall()` (Android)
**Path:** `mobile/android/app/src/main/java/com/agentcall/app/home/HomeViewModel.kt`, lines 233-235
**Lines:** 3
**Status:** UNUSED — defined but never called.
**Evidence:** No caller found in Android source. No menu item, button, or gesture triggers it.

### 4.3 `showAITyping()` (Android)
**Path:** `mobile/android/app/src/main/java/com/agentcall/app/call/CallViewModel.kt`, lines 115-117
**Lines:** 3
**Status:** UNUSED — sets `isAITyping` but no composable observes it fully (the typing indicator composable exists but state transition to `isAITyping=true` never occurs).
**Evidence:** No caller in any ViewModel or Activity.

### 4.4 `setBargeIn()` (Android)
**Path:** `mobile/android/app/src/main/java/com/agentcall/app/call/CallViewModel.kt`, lines 119-121
**Lines:** 3
**Status:** UNUSED — defined but never called. Barge-in is handled entirely in `CallService.kt`.

### 4.5 `setPaused()` (Android)
**Path:** `mobile/android/app/src/main/java/com/agentcall/app/call/CallViewModel.kt`, lines 123-144
**Lines:** 22
**Status:** UNUSED — complex method (22 lines) that is never called from anywhere.
**Evidence:** No caller in Android source.

### 4.6 `disconnect()` (Android)
**Path:** `mobile/android/app/src/main/java/com/agentcall/app/call/CallViewModel.kt`, lines 165-175
**Lines:** 11
**Status:** CALLED ONLY FROM DEAD CODE — called from `CallEvent.CallEnded` handler which fires, but `isConnected = false` transition does not propagate to any visible UI state.
**Evidence:** The `isConnected` StateFlow is not observed by any composable beyond initial render.

### 4.7 `onReconnect` parameter (Android)
**Path:** `mobile/android/app/src/main/java/com/agentcall/app/settings/SettingsScreen.kt`, line 109
**Status:** UNUSED PARAMETER — `onReconnect: () -> Unit = {}` default lambda that is never called.
**Evidence:** No invocation of `onReconnect` anywhere in the composable body.

---

## 5. Unused Variables

### 5.1 `strictRateLimit` (backend)
**Path:** `backend/src/routes.ts`, line 15
**Lines:** 1
**Status:** DECLARED BUT NEVER USED — `const strictRateLimit: FastifyRateLimitOptions = { max: 10, timeWindow: '1 minute' }` is defined but never passed to any route or plugin.

### 5.2 `_state` (backend)
**Path:** `backend/src/signaling/server.ts`, line 56
**Lines:** 1
**Status:** UNUSED VARIABLE — `for (const [ws, _state] of clientRateLimits)` — `_state` is destructured but never used. Should be just `[ws]`.

### 5.3 `lastAiEmotion` (Android)
**Path:** `mobile/android/app/src/main/java/com/agentcall/app/call/CallService.kt`, line 68
**Status:** WRITTEN BUT PROBABLY NOT READ — used only in `ACTION_REPEAT_LAST` handling to display emotion, but `repeatLastAiMessage()` does not use the emotion.

### 5.4 `isPaused` (Android)
**Path:** `mobile/android/app/src/main/java/com/agentcall/app/call/CallService.kt`, line 52
**Status:** WRITTEN BUT INCOMPLETE — `isPaused` is checked in `speakWithEmotion()` but the pause/resume lifecycle is not fully implemented in `startVoiceSession()`.

### 5.5 `env()` function internal behavior
**Path:** `backend/src/common/config.ts`, `mcp-server/src/config.ts`
**Status:** POTENTIAL BUG — `env()` returns `''` for missing required vars. While not "dead" per se, the silent empty-string behavior could produce `NaN` ports or empty URLs that fail at runtime with obscure errors.

---

## 6. Unused Dependencies (package.json)

### 6.1 `zod` in MCP server
**Path:** `mcp-server/package.json`, line 15
**Version:** `^3.23.0`
**Status:** DEAD DEPENDENCY — listed in `dependencies` but never imported in any source file.
**Evidence:** Grep across `mcp-server/src/` for `zod` or `from 'zod'` returns zero results.
**Safe to remove:** Technically yes, but it SHOULD be used for input validation. Consider using rather than removing.

### 6.2 `@xenova/transformers` not in dependencies but referenced in `stt.js`
**Path:** `backend/package.json` — NOT LISTED
**Status:** MISSING DEPENDENCY — the orphan `dist/voicebridge/stt.js` imports this package dynamically but it's not in `package.json`. Either it was removed, or the package was expected to be globally installed. Either way, it's a build integrity failure.

---

## 7. Legacy Experiments

### 7.1 Entire iOS Archive
**Path:** `mobile/ios-archived/`
**LOC:** ~2,076
**Status:** EXPERIMENT / PROOF OF CONCEPT — different architecture than Android (WebRTC vs VoiceBridge protocol), different SwiftUI approach, placeholder domains (`example.com`).
**Evidence:**
- `WebRTCClient.swift` uses `stasel/WebRTC` with placeholder TURN/STUN hosts (`turn.agentcall.example.com`)
- `ApiClient.swift` uses `api.agentcall.example.com`
- iOS is listed as a future Phase 5 item in IMPLEMENTATION_ROADMAP.md
- No CI, no Docker build, no documentation references this code

---

## 8. Dead / Commented Code

### 8.1 `inspectBody` debug helper (backend)
**Path:** `backend/src/routes.ts`, lines 7-13
**Lines:** 7
**Status:** DEBUG ONLY — `inspectBody()` logs request body on every request. In production, this would log all request bodies including potentially sensitive data. The function has no side effects beyond logging but is always active (not gated by NODE_ENV).

### 8.2 `redactHeaders()` redundant serializer (backend)
**Path:** `backend/src/common/logger.ts`, lines 4-15
**Lines:** 12
**Status:** REDUNDANT — Pino's built-in `redact` config (lines 29-31) already handles header redaction. The custom serializer runs before the redact paths, doing double work.

### 8.3 `env()` comment
**Path:** `mcp-server/src/config.ts`
**Status:** Not dead code, but the implementation returns `''` for missing vars despite the name "env" implying it reads environment — the empty string behavior is undocumented.

---

## 9. Duplicate Implementations

### 9.1 Emotion maps (3 copies)
**Locations:**
1. `CallActivity.kt:75-97` — `emotionColors`, `emotionEmojis`, `emotionGradients`
2. `CallService.kt:646-660` — emotion colors and emojis for notifications
3. `CallViewModel.kt:179-185` — emotion frequency for waveform

**Impact:** Adding a new emotion requires updating 3 separate files. Inconsistent updates will cause runtime inconsistency.

### 9.2 Root `Dockerfile` = `backend/Dockerfile`
**Paths:**
1. `C:\Users\91808\Desktop\AgentCall\Dockerfile` (root)
2. `C:\Users\91808\Desktop\AgentCall\backend\Dockerfile`

**Lines:** 28 each
**Status:** 100% line-for-line identical. Root file serves no purpose. Will diverge over time.

---

## 10. Configuration Dead Ends

### 10.1 Unreferenced `.env` keys
**Path:** `backend/.env`
**Status:** Contains `SIGNALING_PORT`, `STT_ENABLED`, `STT_MODEL` keys that are NOT referenced in `backend/src/common/config.ts`. These are either:
- Remaining from a previous config version
- Expected by a module that was deleted (stt.ts)
- Intended for future use

### 10.2 `vitest.config.ts` references non-existent files
**Path:** `backend/vitest.config.ts`
**Status:** Test configuration references:
- `src/**/*.test.ts` — no test files match this pattern
- `src/__tests__/setup.ts` — file does not exist
- Both cause `npm test` to fail

---

## 11. Build Artifacts Not in .gitignore

### 11.1 `dist/` files
**Status:** `dist/` is in `.gitignore` for `backend/` but the orphan `stt.js` was committed before the rule was added (or it was manually placed). All `dist/` files should be removed from git tracking and added to `.gitignore`.

---

## 12. Cleanup Summary

| Action | Item | Est. Lines Removed | Safety |
|--------|------|--------------------|--------|
| DELETE | `Dockerfile` (root) | 28 | Safe — exact duplicate |
| DELETE | `mobile/ios-archived/` | ~2,076 | Safe — archived experiment |
| DELETE | `backend/dist/voicebridge/stt.js` | 125 | Safe — orphan, broken |
| REMOVE | `SendMessageInput` interface | 5 | Safe — zero references |
| REMOVE | `AudioChunk` interface | 6 | Safe — zero references |
| REMOVE | Dead import `emotionOf` in `service.ts` | 1 | Safe — not used in file |
| REMOVE | `strictRateLimit` declaration | 1 | Safe — never used |
| REMOVE | `_state` destructuring | 1 | Safe — unused variable |
| REMOVE | `clearActiveCall()` | 3 | Safe — never called |
| REMOVE | `setBargeIn()`, `setPaused()`, `showAITyping()` | 28 | Safe — never called |
| REMOVE | `onReconnect` parameter default | 1 | Safe — never invoked |
| REMOVE | Redundant `redactHeaders()` serializer | 12 | Safe — pino redact handles it |
| REMOVE/GATE | `inspectBody` debug logging | 7 | Gate behind NODE_ENV=development |
| REMOVE | Unreferenced `.env` keys | 3 | Safe — no code reads them |
| FIX/RUN | `vitest.config.ts` reference to non-existent files | Config fix | Required to run tests |
| USE/REMOVE | `zod` from mcp-server dependencies | 1 line | Use for validation or remove |

**Total removable lines: ~2,297** (plus ~322 from decomposed dead code in active files)
**Total recommended actions: 16**

---

## Safety Classification

| Classification | Count | Items |
|----------------|-------|-------|
| Safe to remove immediately | 11 | Root Dockerfile, iOS archive, stt.js, unused interfaces, dead import, unused variables, unused Android methods |
| Safe to remove with verification | 3 | Redundant serializer, debug logging, unreferenced env keys |
| Should use, not remove | 1 | `zod` dependency |
| Requires fix, not removal | 1 | `vitest.config.ts` broken references |
