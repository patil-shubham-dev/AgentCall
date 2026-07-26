# Project Sustainability — VoiceBridge

## Documentation

### Current State
- 179 markdown files, ~24,000 lines of documentation
- 3-tier architecture docs (overview, detailed, baseline)
- 10 ADRs, 14 known limitations, 34 tech debt items
- Complete API spec, deployment, operations guides
- Community files (CODE_OF_CONDUCT, CONTRIBUTING, SECURITY, SUPPORT)

### Maintenance Burden

| Factor | Assessment | Recommendation |
|--------|------------|----------------|
| Total doc files | 179 — high count | Archive or delete redundant reports from dev cycle |
| Report files | 46 in docs/reports — many are pre-release artifacts | Prune quarterly; keep only actionable reports |
| Design docs (deprecated) | 10 files with historical banners | Keep as-is; no maintenance needed |
| ARCHIVE_INDEX.md | 53 files indexed | Maintain index; content frozen |
| Documentation accuracy | 4 docs known to be inaccurate (API_SPEC, DEPLOYMENT_GUIDE, ARCHITECTURE, DATABASE_GUIDE) | Fix in v1.0.1 (high priority) |

### Sustainability Score: B

Strengths: Well-organized, multi-tier, indexed,
comprehensive. Risks: 4 inaccurate docs could mislead
contributors; 46 reports add noise.

---

## Maintainer Burden

### Current
- **Single maintainer:** @patil-shubham-dev
- **Bus factor:** 1 — if maintainer is unavailable, project stops
- **Time commitment:** Unknown (pre-release phase)

### Projected (post-release)

| Activity | Frequency | Time per Week |
|----------|-----------|---------------|
| Issue triage | 5-10 new issues/week | 1-2 hours |
| PR review | 2-5 PRs/week | 2-4 hours |
| Dependency updates | 2-5 Dependabot PRs/week | 30 min |
| Community support | Discussions, questions | 1-2 hours |
| Release management | Monthly | 2 hours/month |
| Documentation updates | As needed | 1 hour/week |
| **Total (estimated)** | | **6-10 hours/week** |

### Sustainability Score: C

Single maintainer is the biggest risk. Recommend:
1. Identify 1-2 additional maintainers within 6 months
2. Document all processes (this document + MAINTAINER_GUIDE.md)
3. Set clear expectations about response times

---

## Issue Management

### Current State
- 0 open issues (pre-release)
- 26 proposed issues documented in INITIAL_GITHUB_ISSUES.md
- Issue templates: bug_report.yml, feature_request.yml, documentation.yml
- Labels: 20 curated labels
- Milestones: v1.0.1, v1.0.2, v1.1, v1.2, v2.0

### Sustainability Score: A

Strengths: Strong template foundation, well-designed labels,
clear milestone structure, issues pre-written. When community
arrives, the process is ready.

---

## Dependency Strategy

### Current Dependencies

| Dependency | Version | Risk | Update Cadence |
|------------|---------|------|----------------|
| Fastify | 5.x | Low — stable, well-maintained | Minor updates quarterly |
| @fastify/websocket | 11.x | Low | With Fastify |
| pg (node-postgres) | 8.x | Low — stable API | Security patches immediately |
| TypeScript | 5.5 | Low | Minor updates quarterly |
| Zod | 3.x | Low — stable | Minor updates quarterly |
| Vitest | 2.x | Low — dev only | Minor updates quarterly |
| Docker | 20-slim | Low | Base image updates monthly |

### Sustainability Score: A

All dependencies are low-risk, well-maintained, and
backward-compatible. No framework churn risk. Dependabot
configured for automated updates.

---

## Release Cadence

| Release Type | Frequency | Scope |
|-------------|-----------|-------|
| Patch (v1.0.x) | As needed (bug fixes, docs) | Minimal, low risk |
| Minor (v1.x.0) | Quarterly | Features, non-breaking changes |
| Major (v2.0.0) | Annual | Breaking changes, architecture shifts |

### Sustainability Score: B

Process is documented, but not yet proven. First few releases
will stress-test the process. Recommend retrospective after
each of the first 3 releases.

---

## Community Management

### Current State
- No active community (pre-release)
- Community files: present (CODE_OF_CONDUCT, CONTRIBUTING, COMMUNITY)
- No community metrics yet

### Projected Growth

| Phase | Timeline | Community Size | Management Needs |
|-------|----------|---------------|------------------|
| Launch | Month 0-1 | 0-10 stars | Respond to first issues |
| Early adoption | Month 1-3 | 10-50 stars | Active triage, first external PRs |
| Growth | Month 3-6 | 50-200 stars | Need 2nd maintainer |
| Established | Month 6-12 | 200-500 stars | Community guidelines mature |

### Sustainability Score: C

Pre-community phase. Risk is that first impressions matter —
if the first 10 issue reporters have a bad experience, word
spreads. Recommend proactive communication in the first 30
days.

---

## Risk Analysis

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Single maintainer burnout | Medium | Critical | Identify 2nd maintainer within 6 months |
| Inaccurate docs mislead users | High | Medium | Fix 4 inaccurate docs in v1.0.1 |
| No community interest | Low | Critical | Improve discoverability, write blog posts |
| Security vulnerability in auth | Medium | Critical | Single-token auth limits blast radius; upgrade to JWT in v1.2 |
| Dependabot PR fatigue | Medium | Low | Batch dependency updates weekly |
| Database schema changes risky | Medium | High | Add migration tooling in v1.1 |
| WebSocket scaling limitations | Medium | Medium | Add connection limits in v1.0.2 |
| iOS developer availability | High | Medium | Evaluate cross-platform framework |
| Competitive pressure | Low | Medium | Focus on MCP-native differentiation |

### Overall Sustainability Score: B-

**Strengths:** Well-organized documentation, excellent
dependency hygiene, prepared issue/milestone infrastructure,
clear release process.

**Weaknesses:** Single maintainer (bus factor 1), unproven
community process, 4 inaccurate documentation files, 46
reports creating upkeep burden.

**Recommendation:** Address bus factor within 6 months. Fix
documentation accuracy immediately. The project foundation is
strong — sustainability depends on community adoption rate.
