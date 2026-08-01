# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.x | ✅ Active development |

## Reporting a Vulnerability

We take security seriously. If you discover a security vulnerability, please:

1. **Do NOT** open a public issue
2. Email us directly or contact the maintainer via [GitHub Security Advisories](https://github.com/patil-shubham-dev/AgentCall/security/advisories)
3. Provide a detailed description of the vulnerability
4. Include steps to reproduce if possible

## Response Timeline

- **24 hours:** Initial acknowledgment
- **7 days:** Assessment and mitigation plan
- **30 days:** Fix deployed (depending on severity)

## Security Guidelines

See [SECURITY_GUIDELINES.md](./docs/SECURITY_GUIDELINES.md) for our security architecture and best practices.

## Scope

The following are in scope for security reports:
- Backend API (`backend/`)
- MCP endpoint (embedded in backend — `backend/src/mcp/`)
- Android app (`mobile/android/`)
- Infrastructure configurations (`infra/`)
- Authentication and authorization flows

The following are out of scope:
- Third-party dependencies (report to respective maintainers)
- Issues requiring physical access to a device
- Social engineering attacks against project maintainers

## Recognition

We thank security researchers who follow responsible disclosure practices. With your permission, we will acknowledge your contribution in our security advisories.
