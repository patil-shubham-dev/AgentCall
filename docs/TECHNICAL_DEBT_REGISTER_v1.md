# Technical Debt Register — VoiceBridge v1.0.0

> All deferred items from RC-1, RC-2, and post-release review. Separated by target version.
> Effort estimates are from code analysis. No implementation scheduled.

---

## Post-v1.0 (Immediately Actionable — High Value, Low Risk)

Items that should be done before any significant feature work begins. These are quick wins that improve code quality without touching architecture.

| # | Item | Effort | Rationale |
|---|------|--------|-----------|
| TD-01 | Remove `POST /api/v1/ready` and `POST /api/v1/recovery/complete` from documentation (already removed in code) | 15 min | Code clean, docs still lie |
| TD-02 | Remove `PrimaryDatabaseSessionRepository` and `PrimaryDatabaseCallbackRepository`. Use `Database*Repository` directly in `database` mode. | 30 min | Dead code — adds no value over instrumentation wrapper |
| TD-03 | Fix API_SPEC.md to match actual routes.ts | 2 hours | Critical documentation debt — currently describes a different system |
| TD-04 | Fix DEPLOYMENT_GUIDE.md to remove nonexistent env vars | 1 hour | Misleads operators |
| TD-05 | Fix ARCHITECTURE.md to describe actual system (remove Suga, add DB modes) | 1 hour | Primary architecture reference is wrong |
| TD-06 | Fix DATABASE_GUIDE.md to use pg.Pool instead of Knex | 30 min | Describes nonexistent dependency |
| TD-07 | Fix PRODUCTION_READINESS.md to remove POST /ready documentation | 15 min | Already removed from code |
| TD-08 | Add `statement_timeout` to pg.Pool config | 15 min | `SET statement_timeout = '5s'` on pool connect |

**Total: ~5.75 hours**

---

## v1.1 (Next Release)

Items that address known limitations with moderate effort and clear benefit.

| # | Item | Effort | Rationale |
|---|------|--------|-----------|
| TD-09 | Add pagination to `SessionRepository.list()` | 4 hours | Scalability — prevents OOM at 100K sessions |
| TD-10 | Add InMemory skip optimization for `database` mode | 8 hours | Save ~32KB/session memory in DB mode |
| TD-11 | Remove all log-only no-op event subscribers | 2 hours | Clean up dispatch overhead (minor) |
| TD-12 | Implement per-pod WebSocket connection limit | 4 hours | Prevent resource exhaustion |
| TD-13 | Add database migration tooling (Knex or node-pg-migrate) | 8 hours | Production operations requirement |
| TD-14 | Add `count()` method to repository interfaces | 2 hours | Avoid full-table iteration in health endpoint |
| TD-15 | Health endpoint: use DB count instead of in-memory list | 2 hours | Reduce memory pressure at scale |
| TD-16 | Add cross-pod session lock via `pg_advisory_lock` | 16 hours | Data integrity for multi-pod deployments |
| TD-17 | Add WebSocket drain on shutdown (notify clients before close) | 4 hours | Improve user experience during rolling update |
| TD-18 | Move `phoneConnections` from module-level global to injected service | 2 hours | Testability |

**Total: ~52 hours**

---

## v2.0 (Major Release)

Items that require significant architecture changes or new dependencies.

| # | Item | Effort | Rationale |
|---|------|--------|-----------|
| TD-19 | Implement multi-user JWT auth with roles | 40 hours | Replace single-token auth |
| TD-20 | Add distributed timer service (Redis-based) | 40 hours | Replace per-process setTimeout |
| TD-21 | Implement notification service (push, email, SMS) | 40-80 hours | Event subscribers actually do work |
| TD-22 | Add metrics aggregation and Prometheus text format endpoint | 16 hours | Replace JSON endpoint with direct Prometheus scrape |
| TD-23 | Add pagination to database repository queries | 16 hours | Scale to 1M+ sessions |
| TD-24 | Add stable WS connection migration (session handoff between pods) | 40 hours | Zero-downtime WS for rolling updates |
| TD-25 | Implement circuit breaker for DB dual-write failures | 8 hours | Proactive failure handling when DB is degraded |

**Total: ~200-240 hours**

---

## Research

Items that need investigation before they can be estimated or scheduled.

| # | Item | Question |
|---|------|----------|
| TD-26 | Notification deduplication on retry | Should notification dispatch be moved out of repository layer? What is the idempotency contract for phone clients? |
| TD-27 | `pg_advisory_lock` vs Redis lock for cross-pod locking | Which adds less latency? Which is simpler to operate? |
| TD-28 | Monotonic clock for timer scheduling | Can `process.hrtime()` replace `Date.now()` for relative delays? What about absolute (DB-stored) timestamps? |
| TD-29 | MetricsCollector LRU eviction | Is a 100-key limit sufficient? Should this be configurable? |
| TD-30 | Schema evolution strategy | Should we use Knex, node-pg-migrate, or raw SQL scripts? What rollback strategy? |

---

## Cleanup (Quick Fixes, No Behavioral Change)

| # | Item | Status | Effort |
|---|------|--------|--------|
| TD-31 | Remove commented-out code from routes.ts | Not checked — assume none | N/A |
| TD-32 | Remove `FINAL_` prefixed audit files from repo root (keep for reference, move to archive) | Many files | 30 min |
| TD-33 | Align `package.json` version with release version (2.0.0 → 1.0.0) | Needs discussion | 1 min |
| TD-34 | Add `"typecheck"` script to CI pipeline | Not in current CI config | 15 min |

---

## Summary

| Category | Items | Total Effort |
|----------|-------|-------------|
| Post-v1.0 (immediate) | 8 | ~5.75 hours |
| v1.1 | 10 | ~52 hours |
| v2.0 | 7 | ~200-240 hours |
| Research | 6 | N/A |
| Cleanup | 4 | ~1 hour |

### Key recommendations

1. **Do TD-01 through TD-08 immediately** — 5.75 hours of documentation fixes that prevent operational errors
2. **Plan v1.1 with TD-09 (pagination) and TD-16 (cross-pod lock)** as the highest-priority technical items
3. **Defer v2.0 items** until production evidence justifies the investment
4. **Run TD-30 (schema evolution research)** before the first schema change
