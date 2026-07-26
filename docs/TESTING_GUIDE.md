# ⚠️ HISTORICAL REFERENCE — Testing Guide

> **This document describes an aspirational testing strategy for a planned multi-service system.**
> **It does NOT describe the current VoiceBridge v1.0 testing setup.**
>
> For actual test setup, see `backend/vitest.config.ts` and `backend/src/__tests__/`.
> For test commands: `npm test`, `npm run test:load`, `npm run test:coverage`.

---

## Testing Philosophy

- Tests are not optional — they are part of the definition of done
- Write tests before or alongside implementation
- A bug fix is not complete without a regression test

## Test Pyramid

```
         /\
        /  \        E2E (5-10%)
       /    \       Integration (20-30%)
      /______\      Unit (60-70%)
```

## Unit Tests

### Framework: Vitest (backend), JUnit + Turbine (Android)

**Current coverage:** 48 tests across 5 files. No automated coverage thresholds.

| Layer | Coverage | Tests |
|-------|----------|-------|
| MetricsCollector | 4 tests | Increment, gauge, timing, sample cap |
| Retry policy | 6 tests | Transient, validation, exhaustion, message patterns |
| InMemory repos | 13 tests | CRUD, findById, findByUserId, list, delete |
| RecoveryManager | 1 test | loadFromDatabase scenario |
| Session lock | 5 tests | Ordering, concurrency, error propagation, recovery |
| Transactions | 2 tests | InMemory transaction execution |
| Auth (HTTP + WS) | 5 tests | Token validation, solo-user rejection |
| Security | 2 tests | SQL injection, path traversal |
| Validation | 4 tests | Required fields, reason enum, empty text |
| Repository invariants | 2 tests | Duplicate creates, isolated deletes |
| Concurrency | 1 test | 50 concurrent creates |

### Rules

- No network, database, or I/O in unit tests
- Mock external dependencies (Event Bus, repositories)
- Test edge cases: empty states, error paths, concurrent access
- One assertion concept per test function

## Integration Tests

**Not implemented in VoiceBridge v1.0.** Integration tests require a live PostgreSQL database and test containers setup that has not been added. See `TECHNICAL_DEBT_REGISTER_v1.md` for planned test infrastructure improvements.

## E2E Tests

- MCP Server → Backend → Mobile pipeline (manual until CI supports Android)
- Use MCP Inspector for tool validation
- Real device testing for voice pipeline

## Validation Gates (VoiceBridge v1.0)

| Gate | Status |
|------|--------|
| Unit tests | 48 tests, 100% pass |
| Lint | 0 ESLint errors |
| TypeScript strict | Clean (no `any`) |
| Load test | 42K ops/sec |
| Integration tests | ❌ Not implemented |

## Test Naming

```
describe('MetricsCollector')
  it('should increment counters')
  it('should set gauges')
  it('should record timings with sample cap')
```

## Mocking

- Use `vi.mock()` for Vitest
- InMemory repositories serve as test doubles (no mocking needed for persistence tests)
