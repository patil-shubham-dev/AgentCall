# GitHub Community Health Report

## Current State

| Health File | Status | Notes |
|-------------|--------|-------|
| `README.md` | ✅ Present | Needs polish (see Phase 3) |
| `LICENSE` | ❌ Missing | Declared as MIT in README but no LICENSE file |
| `CODE_OF_CONDUCT.md` | ✅ Present | Standard Contributor Covenant |
| `CONTRIBUTING.md` | ✅ Present | Contributor guide with setup, standards, PR process |
| `SECURITY.md` | ✅ Present | Vulnerability reporting policy |
| `SUPPORT.md` | ✅ Present | Support channels and response expectations |
| `CHANGELOG.md` | ✅ Present | Keep a Changelog format |

## Issue and PR Templates

| Template | Status | Notes |
|----------|--------|-------|
| `ISSUE_TEMPLATE/bug_report.md` | ✅ Present | Bug report with reproduction steps |
| `ISSUE_TEMPLATE/feature_request.md` | ✅ Present | Feature request with motivation |
| `PULL_REQUEST_TEMPLATE.md` | ✅ Present | PR template with checklist |

## Recommended Additions

### CODEOWNERS (Recommended)

A `CODEOWNERS` file defines who is responsible for code in the repository. For AgentCall:

```gitignore
# .github/CODEOWNERS

# Default owner for entire repo
* @agentcall/maintainers

# Backend ownership
/backend/ @agentcall/backend-team

# MCP server
/mcp-server/ @agentcall/mcp-team

# Mobile apps
/mobile/ @agentcall/mobile-team

# Infrastructure / deployment
/infra/ @agentcall/infra-team

# Documentation
/docs/ @agentcall/docs-team
*.md @agentcall/docs-team
```

### Dependabot (Recommended)

Automated dependency updates for security and maintenance:

```yaml
# .github/dependabot.yml
version: 2
updates:
  - package-ecosystem: "npm"
    directory: "/backend"
    schedule:
      interval: "weekly"
    open-pull-requests-limit: 10

  - package-ecosystem: "npm"
    directory: "/mcp-server"
    schedule:
      interval: "weekly"
    open-pull-requests-limit: 10

  - package-ecosystem: "docker"
    directory: "/backend"
    schedule:
      interval: "weekly"

  - package-ecosystem: "github-actions"
    directory: "/"
    schedule:
      interval: "weekly"
```

### FUNDING.yml (Optional)

If the project accepts sponsorships:

```yaml
# .github/FUNDING.yml
github: [agentcall]
custom: ["https://agentcall.dev/sponsor"]
```

## Summary

- **Present:** 6 of 7 core community health files
- **Missing:** LICENSE file (blocking for public release)
- **Recommended:** CODEOWNERS, dependabot.yml, FUNDING.yml (nice-to-have, not blocking)

The repository meets GitHub community standards for a public open-source project with the exception of the missing LICENSE file.
