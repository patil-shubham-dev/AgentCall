# Maintainer Guide — VoiceBridge

## How to Review PRs

### PR Review Checklist

**Code Quality**
- [ ] Follows TypeScript strict mode (no implicit any)
- [ ] No commented-out code
- [ ] No dead code or console.log
- [ ] No secrets or credentials in code
- [ ] Follows 2-space indent (Prettier)
- [ ] ESLint passes

**Architecture Compliance**
- [ ] Repository pattern for data access (not direct DB calls)
- [ ] Event-driven for domain events (not direct service calls)
- [ ] Auth middleware for all routes
- [ ] Single-purpose route handlers
- [ ] No circular dependencies
- [ ] Import from `@/` aliases, not relative paths deep

**Testing**
- [ ] New features include unit tests
- [ ] Bug fixes include regression tests
- [ ] Tests use in-memory repositories (not real DB)
- [ ] Test names describe behavior, not implementation
- [ ] No test depends on test execution order

**Documentation**
- [ ] API changes reflected in API_SPEC.md
- [ ] New env vars added to README.md table
- [ ] Schema changes added to DATABASE_GUIDE.md
- [ ] CHANGELOG.md updated with change entry
- [ ] If breaking change: ARCHITECTURE_BASELINE.md updated

**Edge Cases**
- [ ] Error paths return structured `{ error, code, details }`
- [ ] Input validation via Zod schema on every API boundary
- [ ] Empty states handled (no sessions, no callbacks, etc.)
- [ ] Concurrent access handled (cross-pod lock if needed)

### Review Process

1. Check PR title matches conventional commit format
2. Run `npm run lint && npm run typecheck && npm test`
3. Review code for checklist items above
4. For large changes: request screenshots or test output
5. Approve or request changes with specific reasoning
6. Squash-merge to `develop` branch
7. Delete feature branch after merge

## How to Triage Issues

### Triage Process

1. **Acknowledge** — Apply `triage` label within 24 hours
2. **Categorize** — Apply type label: `bug`, `enhancement`, `documentation`, `question`
3. **Prioritize** — Apply priority: `critical`, `high`, `medium`, `low`
4. **Scope** — Apply area label: `backend`, `mobile`, `mcp`, `infra`, `ci`
5. **Assign** — Assign to maintainer or leave unassigned for community
6. **Milestone** — Assign to appropriate GitHub Milestone

### Priority Definitions

| Priority | Definition | Response SLA | Fix SLA |
|----------|-----------|-------------|---------|
| Critical | Data loss, security vulnerability, complete outage | 4 hours | 24 hours |
| High | Major feature broken, significant regression | 24 hours | 1 week |
| Medium | Minor bug, missing feature, documentation | 1 week | 1 month |
| Low | Nice-to-have, cosmetic, research | 1 month | Next release |

### Closing Issues

- **Duplicate:** Close with comment linking to original issue
- **Won't fix:** Close with explanation of why
- **Out of scope:** Close with link to PRODUCT_VISION.md
- **Question answered:** Convert to Discussion
- **Stale (no activity 90 days):** Close with "stale" comment

## How to Cut Releases

See [RELEASE_PROCESS.md](./RELEASE_PROCESS.md) for full process.

### Quick Reference

```
1. git checkout develop && git pull
2. Create release branch: git checkout -b release/v<version>
3. Update CHANGELOG.md, VERSION.md, package.json
4. git commit -m "chore(release): v<version>"
5. git checkout main && git merge release/v<version>
6. git tag -a v<version> -m "feat(release): <title>"
7. git push origin main --tags
8. Create GitHub Release
9. git checkout develop && git merge main
10. Delete release branch
```

## How to Update Dependencies

### Regular Schedule

- **Dependabot:** Automated PRs for npm, Docker, GitHub Actions
- **Review cadence:** Weekly (batch Dependabot PRs)

### Review Process

1. Check CHANGELOG of dependency for breaking changes
2. Run `npm test` and `npm run typecheck` on updated branch
3. Check for deprecated APIs in the new version
4. If patch update: merge directly
5. If minor update: merge after testing
6. If major update: create feature branch, full review

### Manual Updates

```bash
npm outdated                      # Check outdated packages
npm update                        # Update within semver range
npm install <package>@latest      # Update to latest
```

## How to Archive Issues

### Archive Trigger
- Issue resolved, PR merged, release deployed
- Stale (no activity for 90 days)
- Duplicate of existing issue
- Out of project scope

### Archive Process

1. **Resolved:** Close with `completed` label, reference merging PR
2. **Stale:** Comment asking for update. If no response in 14 days, close.
3. **Duplicate:** Close with "Duplicate of #XX" comment
4. **Out of scope:** Close with reference to PRODUCT_VISION.md

## How to Manage Milestones

### Milestone Lifecycle

1. **Create:** When planning the next release, create milestone with target date
2. **Populate:** Assign issues during triage and sprint planning
3. **Track:** Update issue status as work progresses
4. **Review:** Before milestone closure, verify all issues are resolved or reassigned
5. **Close:** After release is deployed, close milestone
6. **Create next:** Create milestone for the following release

### Current Milestones

| Milestone | Status | Target |
|-----------|--------|--------|
| v1.0.1 | Pending | Month 1 |
| v1.0.2 | Pending | Month 2 |
| v1.1 | Pending | Quarter 2 |
| v1.2 | Pending | Quarter 3 |
| v2.0 | Future | Year 2 |
