# VoiceBridge v1.1 — Roadmap

## Prioritization

### Critical

| Item | Source | Complexity | Impact | Dependencies |
|------|--------|-----------|--------|-------------|
| Cross-pod session lock (pg_advisory_lock) | L002, TD-16 | High — 16h | High — data integrity in multi-pod | None |
| WebSocket drain on shutdown | L003, TD-17 | Medium — 4h | High — UX during rolling updates | None |

### High

| Item | Source | Complexity | Impact | Dependencies |
|------|--------|-----------|--------|-------------|
| Database migration tooling | L005, TD-13 | Medium — 8h | High — production operations | Schema research (TD-30) |
| Session pagination (list + count) | L014, TD-09, TD-14 | Medium — 6h | Medium — scale to 100K+ | None |
| InMemory skip in db-only mode | L006, TD-10 | Medium — 8h | Medium — memory reduction | None |
| Multi-user auth + JWT | L001 | High — 40h | High — security | None |

### Medium

| Item | Source | Complexity | Impact | Dependencies |
|------|--------|-----------|--------|-------------|
| Per-pod WebSocket connection limit | L010, TD-12 | Medium — 4h | Medium — resource protection | None |
| Statement timeout on DB pool | L008 | Low — 15min | Medium — query safety | None |
| Remove log-only no-op subscribers | L007, TD-11 | Low — 2h | Low — cleanup | None |
| Move phoneConnections to injected service | TD-18 | Low — 2h | Low — testability | None |
| Health endpoint: use DB count | TD-15 | Medium — 2h | Medium — reduce memory pressure | TD-14 (count method) |

### Low

| Item | Source | Complexity | Impact | Dependencies |
|------|--------|-----------|--------|-------------|
| Remove PrimaryDatabase repos | L013, TD-02 | Low — 30min | Low — code cleanup | None |
| Remove log-only subscribers from startup | TD-11 | Low — 2h | Low — minor perf | None |
| Fix API_SPEC.md to match routes | TD-03 | Medium — 2h | Medium — docs accuracy | None |
| Fix DEPLOYMENT_GUIDE.md env vars | TD-04 | Low — 1h | Low — docs accuracy | None |
| Fix ARCHITECTURE.md to describe actual system | TD-05 | Low — 1h | Medium — docs accuracy | None |
| Fix DATABASE_GUIDE.md pg.Pool | TD-06 | Low — 30min | Low — docs accuracy | None |

### Research

| Item | Source | Question |
|------|--------|----------|
| Notification dedup on retry | L012, TD-26 | Should dispatch move out of repository layer? |
| pg_advisory_lock vs Redis lock | TD-27 | Latency and operational complexity comparison |
| Monotonic clock for timers | L011, TD-28 | Can process.hrtime() replace Date.now()? |
| MetricsCollector LRU eviction | L009, TD-29 | Is 100-key limit sufficient? Configurable? |
| Schema evolution strategy | TD-30 | Knex vs node-pg-migrate vs raw SQL? |
| iOS app re-activation | [Unreleased] | Should we rebuild iOS app? React Native vs Swift? |

## Separation: Bug Fixes vs New Features

| Type | Items | Total Effort |
|------|-------|-------------|
| Bug fixes | L008 (statement timeout), TD-12 (WS limit), TD-17 (WS drain) | ~8.25h |
| New features | Cross-pod lock, migration tooling, pagination, JWT auth, InMemory skip | ~78h |
| Documentation fixes | TD-03, TD-04, TD-05, TD-06 | ~4.5h |
| Cleanup | TD-02, TD-11, TD-18 | ~4.5h |
| Research | 6 items | Ongoing |

## Effort Summary

| Priority | Items | Total |
|----------|-------|-------|
| Critical | 2 | ~20h |
| High | 4 | ~62h |
| Medium | 5 | ~12h |
| Low | 6 | ~7h |
| Research | 6 | N/A |
| **Total** | **23** | **~101h** |
