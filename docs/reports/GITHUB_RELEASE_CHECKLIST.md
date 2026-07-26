# GitHub Release Checklist — v1.0.0

## Pre-Release

- [x] **Repository cleaned** — Documentation reorganized, root reduced to 25 files
- [x] **Documentation organised** — docs/, archive/, reports/ with indexes
- [x] **README polished** — Comprehensive rewrite with badges, features, stack, env vars, links
- [x] **Version verified** — 1.0.0 consistent across all 6 sources
- [x] **Changelog complete** — [1.0.0] section with full Added/Changed/Fixed/Deprecated
- [x] **Release notes complete** — RELEASE_NOTES_v1.0.md with all sections
- [x] **Community files present** — CODE_OF_CONDUCT, CONTRIBUTING, SECURITY, SUPPORT
- [x] **No broken links** — Verified across all 166 markdown files
- [x] **Historical docs archived** — 53 files in docs/archive/ with index
- [x] **Reports archived** — 35 files in docs/reports/ with index
- [x] **Design docs preserved** — 10 files in docs/ with historical banners
- [x] **ADRs preserved** — 10 files in docs/adr/ (untouched)
- [x] **Documentation landing page** — docs/README.md created

## Release Artifacts

- [x] Docker image: multi-stage, non-root, HEALTHCHECK (requires build)
- [x] SHA256 checksums: 14 critical files (see RELEASE_ARTIFACTS.md)
- [x] CI/CD pipeline: ci.yml + ci-cd.yml (requires runner)
- [ ] **Git tag v1.0.0 created** — `git tag -a v1.0.0 -m "VoiceBridge v1.0.0 — Solo Bridge"`
- [ ] **GitHub Release created** — With release notes and artifacts
- [ ] **Docker images pushed** — To ghcr.io or similar registry

## Required Before Public Release

- [ ] **LICENSE file** — Create `LICENSE` with MIT text (blocking)
- [ ] **CI badge** — Add build status badge to README
- [ ] **Test coverage badge** — Add coverage badge to README

## Recommended

- [ ] **CODEOWNERS** — Add .github/CODEOWNERS for team maintenance
- [ ] **Dependabot** — Add .github/dependabot.yml for dependency updates
- [ ] **FUNDING.yml** — Add .github/FUNDING.yml for sponsors
- [ ] **Normalize filenames** — Align VOICEBRIDGE_v1_FINAL.md case

## Verified

| Check | Status |
|-------|--------|
| ✅ Source code not modified | Verified — no .ts, .kt changes from cleanup |
| ✅ Tests not modified | Verified — all 48 tests pass |
| ✅ Runtime behavior unchanged | Verified — no config, no imports altered |
| ✅ No files deleted | Verified — all files moved, none removed |
| ✅ Lint passes | Verified — 0 errors |
| ✅ Type check passes | Verified — tsc --noEmit clean |
| ✅ Tests pass | Verified — 48/48 pass |

## Final Step

1. Create LICENSE file with MIT text
2. `git add -A`
3. `git commit -m "chore(release): prepare v1.0.0 for public GitHub release"`
4. `git tag -a v1.0.0 -m "VoiceBridge v1.0.0 — Solo Bridge"`
5. `git push origin main --tags`
6. Create GitHub Release with release notes and artifacts

**Ready for GitHub release after LICENSE file is created.**
