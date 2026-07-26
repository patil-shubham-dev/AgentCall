# AgentCall — Risk Register

> **Date:** 2026-07-26
> **Scope:** All risks identified during the architecture audit and implementation planning phases.

---

## Risk Scoring

| Score | Likelihood | Impact |
|-------|-----------|--------|
| 1 | Very Unlikely (<10%) | Negligible |
| 2 | Unlikely (10-25%) | Minor |
| 3 | Possible (25-50%) | Moderate |
| 4 | Likely (50-75%) | Major |
| 5 | Very Likely (>75%) | Critical |

**Risk Score = Likelihood × Impact** (max 25)

---

## Risk List

### R1 — Architecture Philosophy Violation Blocks Future Development

| Field | Value |
|-------|-------|
| **Description** | The text enrichment, emotion detection, and barge-in classification code violates PRODUCT_VISION.md. If not removed, every new feature will be built on a foundation that contradicts the core philosophy. AI providers may refuse integration. |
| **Likelihood** | 5 (Very Likely — already violated) |
| **Impact** | 5 (Critical — affects product identity, legal, and partnership) |
| **Risk Score** | **25** |
| **Priority** | **CRITICAL** |
| **Mitigation** | Remove the violating code immediately (Phase 0 of refactor plan). No new features should be built until this is resolved. |
| **Contingency** | If removal breaks user experience, add a plugin system for "communication enhancements" that is opt-in and disabled by default. |
| **Owner** | Product Architect |

### R2 — Build Reproducibility Failure

| Field | Value |
|-------|-------|
| **Description** | `backend/dist/voicebridge/stt.js` exists with no source file. The project cannot be rebuilt from source and produce this artifact. This means any deployment that relies on this file will fail when rebuilding from a clean checkout. |
| **Likelihood** | 5 (Very Likely — the source is already missing) |
| **Impact** | 4 (Major — production deployment will fail if this file is needed) |
| **Risk Score** | **20** |
| **Priority** | **CRITICAL** |
| **Mitigation** | Delete the orphan file. Audit all dist/ files for source correspondence. Ensure dist/ is in .gitignore. |
| **Contingency** | If the STT functionality is needed, reimplement the source file properly with the dependency listed in package.json. |
| **Owner** | Backend Lead |

### R3 — Zero Test Coverage

| Field | Value |
|-------|-------|
| **Description** | 0 test files across ~6,500 LOC. Every refactoring step, every bug fix, every new feature has zero regression protection. |
| **Likelihood** | 5 (Very Likely — no tests exist) |
| **Impact** | 5 (Critical — every change risks regression) |
| **Risk Score** | **25** |
| **Priority** | **CRITICAL** |
| **Mitigation** | Fix vitest config immediately. Add unit tests for every extracted service during service decomposition. Make coverage thresholds a hard CI gate. |
| **Contingency** | Manual regression testing before every release until coverage reaches 50%+ for core services. |
| **Owner** | QA Lead |

### R4 — No Authentication Enforcement

| Field | Value |
|-------|-------|
| **Description** | Every backend endpoint is accessible without authentication. There is no JWT validation, no provider key check, no session validation. The `getAuthUser()` function always returns `solo-user`. |
| **Likelihood** | 5 (Very Likely — it's the current state) |
| **Impact** | 5 (Critical — anyone can access any endpoint) |
| **Risk Score** | **25** |
| **Priority** | **CRITICAL** |
| **Mitigation** | Implement JWT auth immediately as the first security measure. Add auth middleware that returns 401 for unauthenticated requests. |
| **Contingency** | Rate-limit all endpoints until auth is implemented to slow brute-force attacks. |
| **Owner** | Security Lead |

### R5 — Service Decomposition Regression

| Field | Value |
|-------|-------|
| **Description** | Extracting services from `voicebridge/service.ts` may introduce subtle bugs in call lifecycle, message ordering, or callback timing. The monolithic code has implicit assumptions about state ordering that may not survive extraction. |
| **Likelihood** | 4 (Likely — complex extraction with implicit state dependencies) |
| **Impact** | 4 (Major — broken calls would be user-facing) |
| **Risk Score** | **16** |
| **Priority** | **HIGH** |
| **Mitigation** | Keep original service.ts as a delegating shim during extraction. A/B test before/after extraction responses. Add integration tests for call lifecycle before starting extraction. |
| **Contingency** | Revert extraction and approach with a different decomposition strategy. |
| **Owner** | Backend Lead |

### R6 — Persistence Migration Data Loss

| Field | Value |
|-------|-------|
| **Description** | Migrating from in-memory Maps to PostgreSQL may lose data if migration scripts are incorrect or if race conditions exist during the cutover. |
| **Likelihood** | 3 (Possible — migration is inherently risky) |
| **Impact** | 4 (Major — user data loss) |
| **Risk Score** | **12** |
| **Priority** | **HIGH** |
| **Mitigation** | Dual-write to both in-memory and PostgreSQL during migration period. Compare outputs. Use database transactions for atomicity. |
| **Contingency** | Keep in-memory fallback. If PostgreSQL fails, switch back without data loss. |
| **Owner** | Backend Lead |

### R7 — Android CallService Decomposition Breaks App

| Field | Value |
|-------|-------|
| **Description** | The 751-line `CallService.kt` god object handles 15+ responsibilities. Decomposing it into focused managers may introduce subtle timing issues, lifecycle bugs, or callback ordering problems. |
| **Likelihood** | 4 (Likely — complex refactoring of core mobile component) |
| **Impact** | 4 (Major — broken call functionality in production) |
| **Risk Score** | **16** |
| **Priority** | **HIGH** |
| **Mitigation** | Keep the original CallService methods as delegation wrappers during extraction. Add Android instrumentation tests for call flow. Test on real devices. |
| **Contingency** | Revert extraction. Ship with original CallService while planning a more incremental approach. |
| **Owner** | Mobile Lead |

### R8 — Event Bus Performance Overhead

| Field | Value |
|-------|-------|
| **Description** | Introducing an Event Bus adds serialization/deserialization overhead and async dispatch latency. For time-sensitive operations (WebSocket signaling → call setup), this could increase latency. |
| **Likelihood** | 3 (Possible — depends on implementation) |
| **Impact** | 3 (Moderate — increased call setup time) |
| **Risk Score** | **9** |
| **Priority** | **MEDIUM** |
| **Mitigation** | Start with synchronous event handlers (same-thread dispatch). Benchmark before migrating to async. Use typed event payloads (no serialization). |
| **Contingency** | Keep direct-call path for latency-critical operations (WebSocket → call setup) while routing non-critical events through Event Bus. |
| **Owner** | Backend Lead |

### R9 — External Dependency Vulnerability

| Field | Value |
|-------|-------|
| **Description** | The project depends on 15+ npm packages, OkHttp, Hilt, Compose, etc. No vulnerability scanning is in place. A critical CVE in a dependency could compromise the entire system. |
| **Likelihood** | 3 (Possible — CVEs are discovered regularly) |
| **Impact** | 4 (Major — compromise of production system) |
| **Risk Score** | **12** |
| **Priority** | **HIGH** |
| **Mitigation** | Add `npm audit` and Docker image vulnerability scanning to CI. Configure Dependabot for automated dependency update PRs. |
| **Contingency** | Pin exact dependency versions. Review and update dependencies monthly. |
| **Owner** | Security Lead |

### R10 — Unclear Product Boundaries

| Field | Value |
|-------|-------|
| **Description** | The current codebase implements features (text enrichment, emotion, barge-in classification) that violate the core product philosophy. Without clear boundaries, developers may continue to add AI reasoning features to AgentCall. |
| **Likelihood** | 4 (Likely — precedent has been set) |
| **Impact** | 3 (Moderate — gradual architecture drift) |
| **Risk Score** | **12** |
| **Priority** | **HIGH** |
| **Mitigation** | Enforce architecture compliance in PR reviews using ARCHITECTURE_CHECKLIST.md. Add a test that validates no text enrichment/emotion functions exist. Document the clear line between "communication" and "AI reasoning" in CONTRIBUTING.md. |
| **Contingency** | Add a CODEOWNERS file that requires architecture review for any PR touching service code. |
| **Owner** | Product Architect |

### R11 — CI Lint Silence Hides Issues

| Field | Value |
|-------|-------|
| **Description** | CI uses `eslint src/ --ext .ts || echo "Lint warnings"` which silently swallows ESLint errors. Lint warnings will never block a PR. Code quality degrades incrementally. |
| **Likelihood** | 5 (Very Likely — already happening) |
| **Impact** | 2 (Minor — code quality degradation, not crashes) |
| **Risk Score** | **10** |
| **Priority** | **MEDIUM** |
| **Mitigation** | Remove `|| echo` from CI. Add `--max-warnings=0`. Make lint failures block the PR. |
| **Contingency** | Run lint as a separate required status check with explicit pass/fail. |
| **Owner** | DevOps Lead |

### R12 — Database Schema Changes Without Migrations

| Field | Value |
|-------|-------|
| **Description** | The project uses Knex.js for migrations but has no database yet. When persistence is added, there's a risk that developers may modify the schema directly instead of creating migrations. |
| **Likelihood** | 3 (Possible — common in early-stage projects) |
| **Impact** | 3 (Moderate — environments get out of sync) |
| **Risk Score** | **9** |
| **Priority** | **MEDIUM** |
| **Mitigation** | Document the migration workflow in CONTRIBUTING.md. Add a CI check that detects uncommitted schema changes. |
| **Contingency** | Snapshot database schema before/after each deployment and alert on differences. |
| **Owner** | Backend Lead |

### R13 — No Monitoring or Alerting

| Field | Value |
|-------|-------|
| **Description** | The production deployment has no monitoring, no alerting, no dashboards. If the system goes down, there is no way to know until users report it. |
| **Likelihood** | 4 (Likely — every system fails eventually) |
| **Impact** | 4 (Major — production outage visibility) |
| **Risk Score** | **16** |
| **Priority** | **HIGH** |
| **Mitigation** | Add Prometheus metrics endpoint to backend. Add health check monitoring via external service (e.g., UptimeRobot, Better Uptime). Set up basic Grafana dashboard for call volume, errors, latency. |
| **Contingency** | Use the hosting provider's built-in monitoring (Suga platform monitoring) until custom monitoring is set up. |
| **Owner** | DevOps Lead |

### R14 — No Deployment Automation

| Field | Value |
|-------|-------|
| **Description** | CI builds Docker images but never deploys them. All deployments are manual. This increases the risk of deployment errors, configuration drift, and long recovery times. |
| **Likelihood** | 4 (Likely — manual processes are error-prone) |
| **Impact** | 3 (Moderate — deployment failures, longer recovery) |
| **Risk Score** | **12** |
| **Priority** | **HIGH** |
| **Mitigation** | Add GitHub Actions deploy workflow with Docker registry push and SSH deploy. Start with staging environment, validate, then add production. |
| **Contingency** | Document the manual deployment process clearly so anyone can do it correctly. |
| **Owner** | DevOps Lead |

### R15 — Orphan Dist Files in Production

| Field | Value |
|-------|-------|
| **Description** | `dist/voicebridge/stt.js` exists in the current build output with no source file. If this file is part of a production deployment, it will run code that cannot be rebuilt or audited. |
| **Likelihood** | 2 (Unlikely — STT may not be used in production) |
| **Impact** | 5 (Critical — unknown code running in production) |
| **Risk Score** | **10** |
| **Priority** | **HIGH** |
| **Mitigation** | Delete the orphan file immediately. Add CI check that every file in dist/ must have a corresponding source in src/. |
| **Contingency** | Tag the current build that includes stt.js for reference, then delete. |
| **Owner** | Backend Lead |

### R16 — Android Infinite Reconnection

| Field | Value |
|-------|-------|
| **Description** | `SignalingClient.kt` recursively calls `connectInternal()` on every WebSocket failure with no max retries or backoff. If the server is down, the app reconnects forever, draining battery and potentially growing a coroutine chain. |
| **Likelihood** | 5 (Very Likely — no backoff implemented) |
| **Impact** | 2 (Minor — battery drain, no crash) |
| **Risk Score** | **10** |
| **Priority** | **MEDIUM** |
| **Mitigation** | Add exponential backoff (1s, 2s, 4s, 8s, max 30s) and max retry limit (10 consecutive failures). |
| **Contingency** | Add a dead-letter state that shows "Server unavailable" to the user and stops retrying. |
| **Owner** | Mobile Lead |

---

## Risk Register Summary

| Priority | Count | Risk IDs |
|----------|-------|----------|
| Critical (Score 20-25) | 4 | R1, R2, R3, R4 |
| High (Score 12-19) | 6 | R5, R7, R9, R10, R13, R14 |
| Medium (Score 8-11) | 5 | R8, R11, R12, R15, R16 |
| Low (Score <8) | 0 | — |

**Total Risks: 15**
**Top Priority Actions:**
1. R1/R4 — Remove architecture violations + implement auth (Phase 0-1)
2. R3 — Fix test infrastructure and add coverage (Phase 0, ongoing)
3. R2 — Remove orphan dist file, fix build integrity (immediate)
4. R13 — Add monitoring before production launch
5. R14 — Add deployment automation before scaling
