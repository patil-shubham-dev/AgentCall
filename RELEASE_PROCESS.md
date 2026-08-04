# Release Process — VoiceBridge

## Branch Strategy

```
main           Production-ready releases
  └─ develop   Integration branch for next release
       ├─ feat/*     New features (branch from develop)
       ├─ fix/*      Bug fixes (branch from develop)
       ├─ hotfix/*   Urgent production fixes (branch from main)
       └─ release/*  Release candidates (branch from develop)
```

### Rules
- `main` is always deployable. Every commit to main is a release candidate.
- `develop` is the integration branch. All feature and fix branches merge here.
- Feature branches: `feat/<short-description>` (e.g., `feat/cross-pod-lock`)
- Fix branches: `fix/<issue-id>-<short-description>` (e.g., `fix/bug-003-statement-timeout`)
- Hotfix branches: `hotfix/<short-description>` (branch from `main`, merge to `main` and `develop`)
- Release branches: `release/v<major>.<minor>.<patch>` (for release candidates)

## Versioning

Semantic Versioning (SemVer 2.0.0):

```
MAJOR.MINOR.PATCH
```

| Increment | When | Example |
|-----------|------|---------|
| MAJOR | Breaking API or architectural changes | v2.0.0 |
| MINOR | New features, backward compatible | v1.1.0 |
| PATCH | Bug fixes, documentation, no behavioral change | v1.0.1 |

Pre-release tags: `v1.1.0-rc.1`, `v1.1.0-beta.1`

## Release Checklist

### Pre-Release

- [ ] All `develop` features for this release merged
- [ ] `CHANGELOG.md` updated with [Unreleased] → [version] section
- [ ] `VERSION.md` updated
- [ ] `package.json` version updated
- [ ] All tests pass: `npm test`
- [ ] Lint and typecheck pass: `npm run lint && npm run typecheck`
- [ ] Integration tests pass: `npm run test:integration`
- [ ] Documentation updated (docs/archive/API_SPEC.md, DEPLOYMENT_GUIDE.md, etc.)
- [ ] Migration scripts reviewed and tested (if applicable)
- [ ] Security review for new features
- [ ] Release notes written in RELEASE_NOTES_v<version>.md

### Release Candidate

- [ ] Create `release/v<version>` branch from `develop`
- [ ] Run full test suite
- [ ] Deploy to staging environment
- [ ] Run smoke tests
- [ ] Run load tests (if performance-related changes)
- [ ] Fix any RC issues on release branch
- [ ] Tag RC: `git tag -a v<version>-rc.<n> -m "Release Candidate <n>"`

### Release

- [ ] Merge `release/v<version>` into `main`
- [ ] Tag release: `git tag -a v<version> -m "Release notes summary"`
- [ ] Push tag: `git push origin main --tags`
- [ ] Create GitHub Release with release notes and artifacts
- [ ] Merge `main` back to `develop` (if hotfixes were applied)
- [ ] Deploy to production

### Post-Release

- [ ] Verify production deployment
- [ ] Run smoke tests against production
- [ ] Monitor metrics for 24 hours
- [ ] Update GitHub Milestone: close current, create next
- [ ] Update ROADMAP.md if applicable

## Hotfix Process

For urgent production issues that cannot wait for the next release cycle.

```
1. Branch from main: git checkout -b hotfix/<description> main
2. Fix the issue
3. Update CHANGELOG.md (add to top, under version)
4. Bump PATCH version
5. Merge to main: git checkout main && git merge hotfix/<description>
6. Tag: git tag -a v<version> -m "Hotfix: <description>"
7. Push: git push origin main --tags
8. Merge to develop: git checkout develop && git merge hotfix/<description>
9. Create GitHub Release
10. Deploy
```

### Hotfix Criteria
- Production outage (service down, data loss, security vulnerability)
- Cannot wait for next scheduled release
- Fix is small and well-understood (< 10 lines changed preferred)

## Rollback Process

### If the release can be reverted:

```bash
# Rollback main to previous tag
git checkout main
git revert HEAD --no-commit
git commit -m "fix(release): revert vX.Y.Z — <reason>"
git tag -a vX.Y.Z+1 -m "Revert vX.Y.Z: <reason>"
git push origin main --tags
```

### If database migrations were applied:

1. **Identify migration rollback:** `npm run migrate:down` (requires migration tooling)
2. **If no migration tooling:** Restore from backup, apply previous schema manually
3. **Deploy previous version:** Use Docker tag from previous release
4. **Verify:** Run smoke tests against rolled-back version

### Rollback decision criteria
- Critical bug discovered post-deployment
- Data integrity issue
- Performance regression exceeding SLOs
- Security vulnerability introduced

## Tagging Strategy

```
v1.0.0           # Production release
v1.0.1           # Patch release
v1.1.0           # Minor release
v2.0.0           # Major release
v1.1.0-rc.1      # Release candidate
v1.1.0-beta.1    # Beta release
v1.0.1-hotfix.1  # Hotfix (alternative: just v1.0.1 with message)
```

### Tag message format
```
<type>: <summary>

<details if needed>
```

Examples:
```
feat(release): VoiceBridge v1.1.0 — Production Hardening

Cross-pod locking, migration tooling, WebSocket drain, session pagination.
```

```
fix(release): v1.0.1 — Documentation fixes and statement timeout

Fixes docs/archive/API_SPEC.md, DEPLOYMENT_GUIDE.md, ARCHITECTURE.md.
Adds statement_timeout=5s to PostgreSQL pool.
```
