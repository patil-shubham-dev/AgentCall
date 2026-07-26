# AgentCall — Repository Cleanup Plan

> **Date:** 2026-07-26
> **Do NOT perform the cleanup — only generate the plan.**

---

## Cleanup Categories

| Category | Items | Est. Effort |
|----------|-------|-------------|
| Folder restructuring | 4 | 1 day |
| File cleanup | 8 | 2 days |
| Configuration cleanup | 6 | 1 day |
| Build cleanup | 4 | 1 day |
| Dependency cleanup | 2 | 2 hours |
| Naming cleanup | 3 | 1 day |
| Documentation cleanup | 5 | 1 day |
| Git cleanup | 2 | 1 day |

---

## 1. Folder Restructuring

### 1.1 Remove `mobile/ios-archived/`

**Action:** Archive the iOS project. It is an experiment with a different architecture (WebRTC vs VoiceBridge protocol) that has diverged from the current approach.

**Plan:**
1. Tag the current state: `git tag archive/ios-archived-v1 HEAD`
2. Create branch `archive/ios` and move the iOS directory there
3. Delete from main branch
4. Update `mobile/README.md` to reference the archive branch

**Files affected:** `mobile/ios-archived/` (entire directory, ~2,076 LOC, 15 files)

### 1.2 Remove Root `Dockerfile`

**Action:** Delete the root-level Dockerfile — it is a 100% duplicate of `backend/Dockerfile`.

**Rationale:** `docker-compose.yml` references `backend/Dockerfile` and `mcp-server/Dockerfile`. CI references `./backend` and `./mcp-server` paths. Root Dockerfile is dead code.

**Files affected:** `./Dockerfile` (28 lines)

### 1.3 Add `dist/` to `.gitignore` and Remove From Tracking

**Action:** Ensure all `dist/` directories are in `.gitignore`. Remove existing tracked dist files from git tracking.

**Files affected:** `.gitignore` at root level; all `backend/dist/` files currently tracked

### 1.4 Remove `.lavish/` Directory

**Action:** If `.lavish/` is not needed, remove it. (This appears to be an AI agent config directory that was not documented in the repo structure.)

**Files affected:** `.lavish/` (entire directory)

---

## 2. File Cleanup

### 2.1 Remove Orphan `dist/voicebridge/stt.js`

**Action:** Delete the orphan build artifact. The source file (`stt.ts`) was deleted but the dist file remains. It cannot be rebuilt and references a missing dependency (`@xenova/transformers`).

**File:** `backend/dist/voicebridge/stt.js` (125 lines)

### 2.2 Remove Dead Interfaces

**Action:** Delete unused type definitions.

| Interface | File | Lines |
|-----------|------|-------|
| `SendMessageInput` | `backend/src/voicebridge/types.ts:89-93` | 5 |
| `AudioChunk` | `backend/src/voicebridge/types.ts:94-99` | 6 |

### 2.3 Remove Dead Variables and Imports

| Item | File | Lines |
|------|------|-------|
| `strictRateLimit` | `backend/src/routes.ts:15` | 1 |
| `_state` (unused destructuring) | `backend/src/signaling/server.ts:56` | 1 |
| `emotionOf` import | `backend/src/voicebridge/service.ts:13` | 1 |

### 2.4 Remove Dead Android Methods

| Method | File | Lines |
|--------|------|-------|
| `clearActiveCall()` | `HomeViewModel.kt:233-235` | 3 |
| `showAITyping()` | `CallViewModel.kt:115-117` | 3 |
| `setBargeIn()` | `CallViewModel.kt:119-121` | 3 |
| `setPaused()` | `CallViewModel.kt:123-144` | 22 |
| `onReconnect` parameter default | `SettingsScreen.kt:109` | 1 |

### 2.5 Remove Redundant `redactHeaders()` Serializer

**Action:** Remove the custom `redactHeaders()` function from `backend/src/common/logger.ts` (lines 4-15). Pino's built-in `redact` config (lines 29-31) already handles header redaction.

### 2.6 Gate `inspectBody()` Behind NODE_ENV

**Action:** Wrap `inspectBody()` call in `routes.ts` with a `config.nodeEnv !== 'production'` check.

---

## 3. Configuration Cleanup

### 3.1 Fix `vitest.config.ts`

**Action:** Remove references to non-existent test files and setup file. Replace with correct configuration:

```typescript
// Current (broken):
include: ['src/**/*.test.ts'],
setupFiles: ['src/__tests__/setup.ts'],

// Fixed:
include: ['src/**/*.test.ts'],  // Create at least one test file
// Remove setupFiles reference until setup file exists
```

### 3.2 Remove Unreferenced `.env` Keys

**Action:** Remove `SIGNALING_PORT`, `STT_ENABLED`, `STT_MODEL` from `backend/.env` — no code references these keys.

### 3.3 Standardize Docker Base Images

**Action:** Choose one base image family:
- `node:20-slim` (Debian-based, larger but more compatible)
- `node:20-alpine` (smaller, musl-based)

Recommend `node:20-alpine` for consistent smaller image sizes. Apply to both `backend/Dockerfile` and `mcp-server/Dockerfile`.

### 3.4 Add `.dockerignore` Files

**Action:** Create `.dockerignore` at root, `backend/`, and `mcp-server/` to exclude:
```
node_modules/
.git/
.gitignore
*.md
dist/  (excluded from build context — rebuilt in Docker)
.env
```

### 3.5 Add ESLint Config for MCP Server

**Action:** Create `.eslintrc.json` in `mcp-server/` matching the backend config rules. Currently the `lint` script in `mcp-server/package.json` will fail because no config exists.

### 3.6 Add Pre-commit Hook Configuration

**Action:** Configure husky + lint-staged:
- Install `husky`, `lint-staged` at root level
- Create `.husky/pre-commit` hook
- Create `.lintstagedrc` with ESLint + tsc for staged `.ts` files

---

## 4. Build Cleanup

### 4.1 Disable Source Maps in Production

**Action:** Set `"sourceMap": false` in production-specific tsconfig or during `npm run build`. Source maps should only be generated in development.

**Files affected:** `backend/tsconfig.json`, `mcp-server/tsconfig.json`

### 4.2 Add Type Declarations for MCP Server

**Action:** Enable `"declaration": true` in `mcp-server/tsconfig.json` to generate `.d.ts` files.

### 4.3 Add Root-Level Scripts

**Action:** Create root `package.json` with scripts:
```json
{
  "scripts": {
    "dev": "cd backend && npm run dev",
    "build": "cd backend && npm run build && cd ../mcp-server && npm run build",
    "test": "cd backend && npm test",
    "lint": "cd backend && npm run lint && cd ../mcp-server && npm run lint",
    "typecheck": "cd backend && npm run typecheck && cd ../mcp-server && npm run typecheck"
  }
}
```

### 4.4 Add `.editorconfig`

**Action:** Create root `.editorconfig`:
```ini
root = true

[*]
indent_style = space
indent_size = 2
end_of_line = lf
charset = utf-8
trim_trailing_whitespace = true
insert_final_newline = true
```

---

## 5. Dependency Cleanup

### 5.1 Remove or Use `zod`

**Action:** Either:
- (Recommended) Use `zod` for input validation in MCP tool handlers (tools.ts) and add validation schemas
- Or remove from `mcp-server/package.json` if not used

Keep the dependency — it was clearly intended for validation and should be used.

### 5.2 Audit `package.json` Dependencies

**Action:** Review all dependencies in `backend/package.json` and `mcp-server/package.json`:
- Remove any packages not imported in source
- Check for version drift between backend and mcp-server (e.g., pino versions)
- Ensure all imported packages are listed in dependencies (not devDependencies)

---

## 6. Naming Cleanup

### 6.1 Standardize Docker Image Tags

**Action:** Change CI Docker build tags from `ic-backend`/`ic-mcp` to `ac-backend`/`ac-mcp` to match compose service names (`ac-backend`, `ac-mcp`).

**File:** `.github/workflows/ci.yml`

### 6.2 Standardize Package Names

**Action:** Rename `backend/package.json` name from `@agentcall/voicebridge` to `@agentcall/backend` to match repo conventions.

### 6.3 Standardize API Field Naming

**Action:** Choose one convention for API fields:
- API_SPEC.md uses `camelCase` (`providerId`, `userId`, `callId`)
- `mcp-server/client.ts` sends `snake_case` (`user_id`, `agent_id`, `call_id`)
- Standardize on `camelCase` per API_SPEC.md

---

## 7. Documentation Cleanup

### 7.1 Add Implementation Status to API_SPEC.md

**Action:** Annotate each endpoint and tool with implementation status:
```markdown
- POST /calls  ✅ Implemented
- POST /providers  ❌ Not implemented (Phase 4)
- query_presence  ❌ Not implemented (Phase 4)
```

### 7.2 Update README.md Feature Claims

**Action:** Audit README.md against actual implementation. Remove or mark aspirational features.

### 7.3 Update CHANGELOG.md

**Action:** Either add real changelog entries or mark as "Pre-release — no releases yet."

### 7.4 Add Getting Started Guide

**Action:** Create `GETTING_STARTED.md` with:
1. Prerequisites (Node.js 20, Docker)
2. Clone and install
3. Environment configuration
4. Running in development mode
5. Running tests
6. Building for production
7. Troubleshooting

### 7.5 Clean Up Deprecated Documentation Files

**Action:** Review status of docs marked as `[DEPRECATED]` in the documentation migration. If they are no longer referenced, remove them.

---

## 8. Git Cleanup

### 8.1 Remove Tracked Build Artifacts

**Action:** Remove `dist/`, `*.js.map` files from git tracking:

```bash
git rm --cached -r backend/dist/
git rm --cached -r mcp-server/dist/
echo "dist/" >> .gitignore
git add .gitignore
git commit -m "chore: remove tracked build artifacts, add dist to gitignore"
```

### 8.2 Squash Cleanup Commits

**Action:** After all cleanup PRs are merged, optionally squash into a single "chore: repository cleanup" commit for a cleaner history.

---

## Cleanup Order

```
Phase A (Day 1): Safe removals — dead code, unused files, unused variables
  1. Delete root Dockerfile
  2. Delete dist/voicebridge/stt.js
  3. Delete dead interfaces and variables
  4. Delete dead Android methods
  5. Remove redundant logger serializer
  6. Archive ios-archived

Phase B (Day 2): Configuration fixes
  1. Fix vitest.config.ts
  2. Remove unreferenced .env keys
  3. Add .dockerignore files
  4. Add ESLint config for MCP server
  5. Add .editorconfig

Phase C (Day 3): Build and naming
  1. Standardize Docker image tags
  2. Standardize package name
  3. Standardize API field naming
  4. Disable source maps in production
  5. Add root-level scripts

Phase D (Day 4): Documentation
  1. Update API_SPEC.md with implementation status
  2. Update README.md
  3. Create GETTING_STARTED.md
  4. Update CHANGELOG.md
  5. Clean up deprecated docs

Phase E (Day 5): Git and dependencies
  1. Remove tracked dist files
  2. Audit/clean up dependencies
  3. Add pre-commit hooks
  4. Final review and squash
```

**Total estimated effort: 5 days**
