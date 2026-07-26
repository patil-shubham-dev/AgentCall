# Release Artifacts — VoiceBridge v1.0.0

## Source Integrity — SHA256 Checksums

| File | SHA256 |
|------|--------|
| `backend/Dockerfile` | `A97C6479800353652EE925CBEA0278D3E0C5394F8550BF685074097D96E9D024` |
| `infra/docker-compose.yml` | `810068383C7DAEC1C40657AB9A59BFE80C4F82E577FC23B8043C9D324513BF05` |
| `backend/.env.example` | `7E3034EDB178706D6CE07CF477E47CD83581B008D08B18C53BCE6BCD33EA2AEF` |
| `backend/package.json` | `DCB3C5A9BEFDE67EFFBAE4784B0346F5583EB6804CAE67268087857DD060500A` |
| `CHANGELOG.md` | `5166F42C7E4B560E2DD32587F9EF1EF033B179D2A046B6E325D2D799240E7C89` |

### Kubernetes Manifests

| File | SHA256 |
|------|--------|
| `infra/k8s/01-namespace.yaml` | `60453749ECEABB25A21D732703AA7A5BDC9DB1D143EA975C761CA9233272A0CC` |
| `infra/k8s/02-secret-template.yaml` | `009077DF08F94FA51200022171C20427A086321FB977BCD273532A221338EC65` |
| `infra/k8s/03-configmap.yaml` | `828F6307178E0A9AB38F53B4E14B67ED0F5F601C27ADE9D8989C073992DD2EDE` |
| `infra/k8s/04-deployment.yaml` | `E658C965F8D4D6BA00D8474276B2F1DEF24957A4B9D3DB08509E7CA0D47D077F` |
| `infra/k8s/05-service.yaml` | `E2A7EDED5D21ABD7E181895F9BF1253AB79229549F978CD57A4895F28EF2B4AB` |
| `infra/k8s/06-ingress.yaml` | `EF89AB2B3EFCFBD312C971B41E9877176F546BE0345F2CE6BAE552BDAA8E219C` |
| `infra/k8s/07-hpa.yaml` | `92839CBCCB8C6BCA5798CFA13E2D9E0811F484AD34BEC19DA9548070ABD3CB34` |
| `infra/k8s/08-pdb.yaml` | `E4105B9B79F2136EB8AA736CF549094D3CBB2B28BC7169ABAD1FD11262E421C8` |
| `infra/k8s/09-network-policy.yaml` | `37C7C7BB4D4F75E2977929869223F6B4BA5875D880774D250617DFA1F0352DF5` |

## Image Build

- **Base image:** `node:20-slim` (glibc compat for onnxruntime)
- **Non-root user:** `appuser:appgroup` (UID 1001:GID 1001)
- **HEALTHCHECK:** `GET /api/v1/health` every 10s, timeout 5s, start-period 10s, 3 retries
- **Filesystem:** Read-only at runtime
- **Docker not available on this dev workstation** → build and push must be executed on the CI runner or a Docker-capable host.

## Docker Compose Package

`infra/docker-compose.yml` includes:
- `voicebridge-backend` service (builds from `./backend`)
- `postgres` service (PostgreSQL 16, health-checked)
- Shared network, dependency graph (backend waits for postgres)
- Port mapping: 4000:4000

## Environment Template

`backend/.env.example` defines all required variables. Critical settings:
- `SERVICE_TOKEN` — single shared auth token (generate via `openssl rand -hex 32`)
- `DATABASE_URL` — PostgreSQL connection string
- `PERSISTENCE_MODE` — `memory`, `database`, or `dual-write`

## Release Tag

```bash
git tag -a v1.0.0 -m "VoiceBridge v1.0.0 — Solo Bridge"
git push origin v1.0.0
```

## Container Registry (if using CI/CD)

Images are published to `ghcr.io` via the `ci-cd.yml` pipeline on push to `main`:
- `ghcr.io/<repo>/voicebridge-backend:<sha>`
- `ghcr.io/<repo>/voicebridge-backend:latest`
