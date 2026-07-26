# Security Review — RC-1

## Authentication

### HTTP API Auth

**Issue: No actual authentication.** `getAuthUser()` returns `{ userId: 'solo-user', role: 'user' }` for all requests that don't match `SERVICE_TOKEN`:

```typescript
// routes.ts:38-48
async function getAuthUser(request: FastifyRequest): Promise<AuthContext> {
  const header = request.headers.authorization;
  if (!header?.startsWith('Bearer ')) {
    return { userId: 'solo-user', role: 'user' };
  }
  const token = header.slice(7);
  if (token === config.serviceToken) {
    return { userId: 'service', role: 'service' };
  }
  return { userId: 'solo-user', role: 'user' };
}
```

- **Severity:** CRITICAL
- **Impact:** Any unauthenticated user can create calls, read transcripts, complete/cancel calls
- **Evidence:** Every API endpoint calls `getAuthUser()` but never checks the role. The `auth` is attached to the request and logged, but never enforced.
- **Likelihood:** 100%
- **Recommendation:** Add role-based authorization checks to every endpoint

### WebSocket Auth

**Issue: WebSocket `/phone` endpoint authenticates via query parameter only** (no signature, no token):

```typescript
// server.ts:89-90
const url = new URL(req.url ?? '/', 'http://localhost');
const userId = url.searchParams.get('user_id') ?? 'solo-user';
```

- **Severity:** CRITICAL
- **Impact:** Any client can connect as any user by setting `?user_id=admin`
- **Evidence:** The `user_id` is taken directly from the query string with zero validation
- **Likelihood:** 100%
- **Recommendation:** Require a signed token or session-based auth for WebSocket connections

### Ready/Recovery Endpoints

**Issue: `POST /api/v1/ready` and `POST /api/v1/recovery/complete` have no auth:**

```typescript
// routes.ts:335-343
app.post('/api/v1/ready', async (_request, reply) => {
  ready = true;
  return reply.status(200).send({ status: 'ready' });
});
app.post('/api/v1/recovery/complete', async (_request, reply) => {
  recoveryDone = true;
  return reply.status(200).send({ status: 'recovery_complete' });
});
```

- **Severity:** HIGH
- **Impact:** Anyone can mark the service as ready by sending a POST. This could bypass readiness checks.
- **Likelihood:** Low (internal network), but HIGH if exposed
- **Recommendation:** Remove these endpoints or restrict to `role: 'service'`

## Authorization

**Issue: No per-user isolation.** There is no authorization check that user A can only access user A's sessions. The `userId` parameter in API bodies is trusted from the client:

```typescript
// routes.ts:133
const userId = (body.user_id as string) ?? 'solo-user';
```

- **Severity:** HIGH
- **Impact:** If multi-tenant was ever needed, user A could access user B's sessions
- **Likelihood:** Low (currently single-user)
- **Recommendation:** Enforce that `auth.userId === request.userId` on user-scoped operations

## Headers

- Helmet CSP configured
- CORS configurable
- HSTS in Caddyfile
- `X-Content-Type-Options`, `X-Frame-Options` set

**Issue:** `crossOriginEmbedderPolicy: false` weakens COEP protection. No `Cross-Origin-Opener-Policy` or `Cross-Origin-Resource-Policy` headers.

## Logging

- Authorization headers redacted via pino `serializers` + `redact` paths
- Production mode suppresses request body logging
- No secrets in logs (verified)

## DoS Protection

- Rate limiting: 100 requests/minute global, stricter per-endpoint
- Body limit: 1MB default
- WebSocket rate limiting: connection-level (30 messages/10s) + connection rate (10/s per IP)
- WebSocket max message size: 256KB
- SessionSweeper interval: 5 minutes (prevents tight loops)
- DB health monitor ping: 15s interval with `unref()`

**Issue:** Rate limiting is per-Fastify-instance. With multiple pods, each pod independently allows 100 req/min. A user could send 100 req/min to each of 10 pods = 1000 req/min total. No centralized rate limiting.

## Dependency Risks

- 7 high-severity npm audit vulnerabilities (transitive)
- No automated dependency update workflow (Dependabot/Renovate)
- `@cyclonedx/cyclonedx-npm` in CI but SBOM generation is `|| true` (silently fails)

## Secret Exposure

- `SERVICE_TOKEN` in env, validated at startup
- `DATABASE_URL` in env (contains credentials)
- K8s Secret template has placeholder values
- Docker Compose uses `.env` file

## Privilege Escalation

- Docker non-root user (1001)
- `no-new-privileges:true`
- `cap_drop: ALL`
- K8s `allowPrivilegeEscalation: false`

Good container security posture.

## Multi-Tenant Readiness

- **Not ready.** Auth is single-user only. The entire auth system needs a redesign for multi-tenant support.
- `solo-user` fallback means every user is the same user

## Security Scan Results

From `SECURITY_AUDIT.md`: 0 critical, 3 medium (dependency vulns, WS auth, per-user auth), 5 low.

**All 3 medium findings remain unaddressed.**

## Score

**Security: 4/10**

Deducted for: absent HTTP auth enforcement (CRITICAL), absent WebSocket auth (CRITICAL), no per-user authorization (HIGH), unprotected ready/recovery endpoints (HIGH), centralized rate limiting missing, 7 high-severity dependencies unpatched, no-authentication model when multi-tenant is stated as a future requirement.
