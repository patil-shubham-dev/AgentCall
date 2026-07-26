# Operational Review — RC-1

## Docker

### Dockerfile Issues

1. **Stage 1 (`deps`) runs `npm ci` twice**: first with `--only=production`, then full install.
   ```dockerfile
   RUN npm ci --only=production && \
       cp -R node_modules /prod_modules && \
       npm ci
   ```
   The second `npm ci` installs dev dependencies for the `builder` stage. This is correct. But:
   - **Issue:** The first `npm ci --only=production` exits with code 1 if `package-lock.json` is not present or outdated. `npm ci` requires a lockfile.

2. **`node_modules/.bin/*` permissions**: `chmod 755 /app/node_modules/.bin/*` — this makes all binaries executable. There's no `.bin` directory in production mode since dev dependencies aren't installed. Minor issue.

3. **HEALTHCHECK uses `node -e` with `require('http')`**: This works but is a slow health check (spins up a Node process for each check). Better to use `wget`, but Alpine doesn't have it by default. The Dockerfile doesn't install `wget` or `curl`.

4. **Package.json is copied but `npm start` is not used**: `CMD ["node", "dist/index.js"]` instead of `CMD ["npm", "start"]`. This is fine — avoids npm overhead.

### docker-compose Issues

1. **`env_file: ../.env`**: The `.env` file is at the repo root for docker-compose, but `backend/.env` for local dev. This is a split that could cause confusion.
2. **Caddy depends on `backend-api` and `mcp-server`**: Docker Compose `depends_on` only waits for container start, not service readiness. Caddy may start proxying before the backend is ready.
3. **No Caddy health check**: The reverse proxy has no health check for upstream services.

## Kubernetes

### Deployment

- Rolling update with `maxUnavailable: 0` and `maxSurge: 1` — correct for zero-downtime
- Liveness probe: `/api/v1/health` — correct
- Readiness probe: `/api/v1/ready` — correct
- Startup probe: `/api/v1/ready`, 12 failures × 5s = 60s startup window — adequate
- Resource limits: 512Mi memory, 1 CPU — reasonable

### Missing K8s Resources

1. **No ServiceAccount** — deployment uses the default namespace service account
2. **No Role/RoleBinding** — least-privilege RBAC not configured
3. **No PodSecurityPolicy** (deprecated in newer K8s, but `PodSecurityAdmission` not configured)
4. **No PriorityClass** — all pods are equal priority

### NetworkPolicy Issue

```yaml
egress:
  - to:
      - namespaceSelector:
          matchLabels:
            kubernetes.io/metadata.name: kube-system
    ports:
      - port: 443
      - port: 53
```

**Issue:** The backend needs to connect to PostgreSQL (external to the cluster). The NetworkPolicy only allows egress to `kube-system` namespace. External connectivity is blocked.

- **Severity:** CRITICAL — the app cannot connect to the database
- **Evidence:** `DatabaseSessionRepository` connects to `config.database.url` which is an external PostgreSQL endpoint
- **Recommendation:** Add egress rule for the database host or use `0.0.0.0/0` with port restrictions

### Ingress

- Uses `ingress-nginx` with cert-manager for TLS
- Single host: `api.voicebridge.example.com` — placeholder domain
- No WebSocket support annotations for nginx-ingress (`nginx.ingress.kubernetes.io/proxy-read-timeout: "60"` may be too low for WebSocket connections). WebSocket connections need longer timeouts (e.g., 3600s).

### HPA

- CPU: 70% target, memory: 80% target
- Min: 2, Max: 10
- **Issue:** The backend has in-memory state (phone WebSocket connections). Scaling up creates pods with empty state. Scaling down kills pods with active connections. HPA must be configured with `behavior.scaleDown` stabilization to prevent rapid scale-down that disconnects users.

### PDB

- `minAvailable: 1` — correct for 2-replica deployment

## Health/Ready/Metrics

- `/api/v1/health` returns DB status, scheduler timer count, session/callback counts — comprehensive
- `/api/v1/ready` returns readiness based on `ready` flag AND DB connection — correct
- `/api/v1/metrics` returns MetricsCollector snapshot — adequate

**Issue:** `ready` flag is `false` by default and only set to `true` via `POST /api/v1/ready` (unauthenticated). The deployment readiness probe calls `GET /api/v1/ready`, which returns `not_ready` until someone calls the POST endpoint. The CI/CD pipeline and deployment scripts do NOT call `POST /api/v1/ready`.

**Severity:** HIGH — the readiness probe will never pass, so the deployment never becomes ready

## Backups

- Documented in `DISASTER_RECOVERY.md` but no automated backup scripts
- No backup verification in CI/CD
- No point-in-time recovery configuration documented (just mentions WAL)

## Monitoring

- Grafana dashboards defined in `GRAFANA_DASHBOARDS.md` — good
- 8 alert rules defined — adequate
- But dashboards are manual JSON — not deployed as ConfigMaps or automated
- No log aggregation (ELK/Loki) configured

## CI/CD Pipeline

- `lint-and-typecheck` → `test` (unit + load) → `security-scan` (npm audit + SBOM) → `build` → `deploy-staging` → `deploy-production`
- **Issue:** `security-scan` runs in parallel with `build` (both depend on `lint-and-typecheck` AND `test`). Wait — looking at the YAML: `build` needs `[lint-and-typecheck, test]`, `deploy-staging` needs `[build, security-scan]`. So `build` and `security-scan` run in parallel after tests pass. This is correct.

**Issue:** `npm run test:load` is part of the test job. The load test takes significant time and increases CI duration. It also has no pass/fail criteria — it just prints results.

**Issue:** CI triggers on push to `main` with `paths` filter for `backend/**`, `mcp-server/**`, `infra/**`. But there's no branch protection requiring PRs — a direct push to `main` triggers the full pipeline including production deployment.

## Deployment

### Staging

- `kubectl set image deployment/voicebridge-backend ...` — updates the image
- `kubectl rollout status ... --timeout=120s` — waits for rollout
- No smoke tests after staging deployment

### Production

- Manual approval gate (GitHub Environments)
- Same deployment command as staging
- Health check: runs a `curl` pod, makes one request, checks HTTP status
- **Issue:** Health check does not verify that `/api/v1/ready` returns `ok`, only that `/api/v1/health` returns any response
- **Issue:** The `health-check` pod might not exist yet when `kubectl run` executes (image pull delay) — no retry logic

## Rollback

- `kubectl rollout undo deployment/voicebridge-backend` — works for application rollback
- No DB schema rollback needed (no migrations)
- No automated rollback on health check failure

## Scaling

- Horizontal: HPA with CPU/memory metrics
- **Issue:** WebSocket connections are per-pod. Scaling up creates pods with no connections. Scaling down kills pods with active connections. No connection draining or migration mechanism.

## Score

**Operational: 5/10**

Deducted for: NetworkPolicy blocks all outbound DB traffic (CRITICAL), readiness probe never passes without manual POST (HIGH), no automated backup/restore scripts, no log aggregation, WebSocket timeout too low for production, HPA could kill WebSocket connections, no smoke tests after deploy, health check is fragile single-pod curl, no branch protection against direct main pushes.
