## Summary

<!-- One sentence summary of the change. -->

Fixes #(issue)

## Type of Change

- [ ] Bug fix (non-breaking change that fixes an issue)
- [ ] New feature (non-breaking change that adds functionality)
- [ ] Breaking change (fix or feature that would break existing functionality)
- [ ] Documentation update
- [ ] Refactor (no functional changes)
- [ ] Test (adding or updating tests)
- [ ] Chore (maintenance, dependencies, CI)

## Checklist

### Code Quality
- [ ] Code follows project style (TypeScript strict mode, 2-space indent)
- [ ] No dead code, no commented-out code, no debug logging
- [ ] Errors handled explicitly — no empty catch blocks
- [ ] Inputs validated at every trust boundary
- [ ] No hardcoded secrets or credentials

### Architecture Compliance
- [ ] Aligns with PRODUCT_VISION.md (no AI reasoning inside AgentCall)
- [ ] Aligns with SYSTEM_ARCHITECTURE.md
- [ ] Aligns with API_SPEC.md
- [ ] Event-driven where applicable
- [ ] Provider-agnostic and device-agnostic
- [ ] No duplicated logic (searched existing code first)

### Testing
- [ ] Unit tests added/updated
- [ ] Integration tests added/updated (if applicable)
- [ ] Manual testing performed
- [ ] All existing tests pass (`npm test`)

### Documentation
- [ ] README.md updated (if user-facing change)
- [ ] API_SPEC.md updated (if API change)
- [ ] ADR created or updated (if architecture change)
- [ ] CHANGELOG.md updated
- [ ] Inline comments updated (if logic change)

## Screenshots

<!-- If applicable, add screenshots to help explain your changes. -->

## Breaking Changes

<!-- Describe any breaking changes and migration steps. -->
- [ ] No breaking changes
- [ ] Breaking changes (described below)

## Additional Notes

<!-- Any information reviewers should know. -->
