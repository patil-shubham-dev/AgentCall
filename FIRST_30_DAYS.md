# First 30 Days — Community Launch Plan

## Daily Maintainer Tasks

| Time | Task | Duration |
|------|------|----------|
| Morning | Check GitHub Issues: new issues, unread comments | 15 min |
| Morning | Check GitHub Discussions: new threads, unanswered questions | 10 min |
| Morning | Check Dependabot PRs: merge or defer | 5 min |
| Afternoon | Review open PRs (if any) | 30-60 min |
| Afternoon | Respond to @mentions and code review requests | 15 min |
| Evening | Quick scan of new issues/PRs for critical items | 10 min |

**Total: ~1-2 hours/day**

---

## Weekly Maintenance

### Monday: Issue Triage
- Review all new issues from the weekend
- Apply labels, assign priorities, set milestones
- Respond to questions within 24h SLA
- Close duplicates with references

### Wednesday: Dependency Updates
- Batch and merge Dependabot PRs
- Run `npm audit` for security vulnerabilities
- Update `docs/DEPENDENCY_GUIDE.md` if major versions changed
- Run full test suite after updates

### Friday: Release Review
- Review all merged PRs for the week
- Update CHANGELOG.md unreleased section
- Evaluate if a patch release (v1.0.x) is needed
- Check issue backlog for stale items

### Weekend: Community Engagement (optional)
- Write blog posts or documentation improvements
- Engage with Hacker News / Reddit threads
- Review longer-term architecture RFCs

---

## Issue Triage Schedule

| Day | Focus | SLA |
|-----|-------|-----|
| Daily | Critical + High priority | 4h for critical, 24h for high |
| Monday | All weekend issues | 24h from Monday morning |
| Wednesday | Mid-week new issues | 48h from creation |
| Friday | End-of-week backlog | Before Monday |

### Triage Labels (Apply in Order)

```
1. Type:  bug | enhancement | documentation | question
2. Area:  backend | mobile | mcp | infra | ci
3. Pri:   critical | high | medium | low
4. Meta:  good first issue | help wanted | triage
```

---

## Release Cadence (First 30 Days)

| Week | Expected | Description |
|------|----------|-------------|
| Week 1 | v1.0.0 | Public release (this launch) |
| Week 2 | Bug fixes | First community bug reports → v1.0.1 candidates |
| Week 3 | Patch | v1.0.1: documentation fixes, statement timeout |
| Week 4 | Planning | Evaluate v1.0.2 scope, community feedback digest |

### Release Triggers

| Trigger | Action | Timeline |
|---------|--------|----------|
| Security vulnerability | Hotfix release | Within 24 hours |
| Critical bug (data loss, crash) | Patch release | Within 1 week |
| Documentation fix | Patch release | Within 2 weeks |
| Non-critical bug | Next scheduled patch | Within 4 weeks |
| Feature PR from community | Next minor release | Within 1 quarter |

---

## Community Engagement

### Week 1: Launch
- [ ] Post GitHub Discussion: "Welcome to AgentCall!"
- [ ] Post GitHub Discussion: "v1.0.0 Launch Discussion"
- [ ] Reply to every comment within 24 hours
- [ ] Tag first Good First Issues
- [ ] Monitor for first external PR

### Week 2: First Contributors
- [ ] Provide thorough, kind code reviews on first PRs
- [ ] Thank every contributor publicly in release notes
- [ ] Create CONTRIBUTORS.md or acknowledge in README
- [ ] Answer "how do I..." questions with documentation links

### Week 3: Community Building
- [ ] Write "Getting Started with AgentCall" blog post
- [ ] Share on Dev.to, Medium, or personal blog
- [ ] Post to MCP community Discord/forums
- [ ] Create demo video (if not done at launch)

### Week 4: Retrospective
- [ ] Post "First Month Recap" discussion
- [ ] Share metrics: stars, forks, contributors, issues closed
- [ ] Vote on top community feature requests
- [ ] Publish v1.1 planning document

---

## Bug Response Targets

| Priority | Acknowledgment | Fix Committed | Release |
|----------|---------------|---------------|---------|
| 🚨 Critical (data loss, security) | 4 hours | 24 hours | Hotfix within 24h |
| 🔴 High (major feature broken) | 24 hours | 1 week | Next patch |
| 🟡 Medium (minor bug) | 48 hours | 1 month | Next release cycle |
| 🟢 Low (cosmetic, nice-to-have) | 1 week | Next milestone | Next minor release |

### Bug Triage Communication Template

```markdown
**Acknowledged.** Thank you for the detailed report.

- **Priority:** [critical/high/medium/low]
- **Milestone:** [v1.0.x]
- **Area:** [backend/mobile/etc.]

We'll investigate and update this issue within [SLA time].
```

---

## Documentation Update Cadence

| Update Type | Frequency | Owner |
|-------------|-----------|-------|
| API_SPEC.md corrections | As needed (within 24h of route change) | Maintainer |
| README.md screenshot updates | Monthly | Maintainer |
| CHANGELOG.md | Weekly (every Friday) | Maintainer |
| DEPLOYMENT_GUIDE.md | Per release | Maintainer |
| Community docs (CONTRIBUTING) | Quarterly | Maintainer + community |

---

## Risk Monitoring (First 30 Days)

| Risk | Early Warning Sign | Action |
|------|-------------------|--------|
| Security vulnerability | Issue filed with security label | Follow SECURITY.md process |
| Negative community response | Hacker News/Reddit criticism | Respond calmly, address valid points |
| Maintainer burnout | Missed triage SLA for 2+ days | Reduce scope, focus on critical only |
| Spam issues/PRs | Multiple low-quality submissions | Apply `invalid` label, close with template |
| Feature request flood | 10+ enhancement issues in first week | Redirect to Discussions, defer to v1.1 |
| Fork with competing vision | Fork with different goals | Acknowledge, no action needed |

---

## Success Criteria (End of 30 Days)

### Must Have
- [ ] 0 unresolved critical/high issues older than 7 days
- [ ] At least 1 external PR merged (or in review)
- [ ] Issue templates working, labels applied to all issues
- [ ] All social announcements published

### Nice to Have
- [ ] 20+ GitHub stars
- [ ] 5+ forks
- [ ] 1 external contributor (PR merged)
- [ ] Blog post or demo video published
- [ ] First community feature request scoped for v1.1

### Metrics Dashboard (Track Weekly)

```csv
Week, Stars, Forks, Issues_Opened, Issues_Closed, PRs_Merged, Discussions
1,    0,     0,     0,            0,             0,          1
2,    _,     _,     _,            _,             _,          _
3,    _,     _,     _,            _,             _,          _
4,    _,     _,     _,            _,             _,          _
```
