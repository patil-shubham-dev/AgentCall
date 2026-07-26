# Launch Checklist — VoiceBridge v1.0.0

## Pre-Launch (24 hours before)

- [ ] Verify `main` branch is up to date with all changes
- [ ] Run final `npm run lint && npm run typecheck && npm test` — all pass
- [ ] Verify `VERSION.md`, `package.json`, `CHANGELOG.md` all match v1.0.0
- [ ] Verify all documentation links resolve (run link checker)
- [ ] Verify README.md renders correctly in GitHub preview
- [ ] Verify issue templates render correctly (Previews tab)
- [ ] Verify PR template renders correctly
- [ ] Verify `.github/labels.yml` is applied (run `gh label clone` or manual sync)
- [ ] Verify CODEOWNERS file is valid
- [ ] Verify Dependabot config is valid (YAML lint)
- [ ] Verify SECURITY.md contact information is current
- [ ] Verify FUNDING.yml has real links (not placeholders)
- [ ] Prepare social media announcement text
- [ ] Prepare Hacker News / Reddit post text (if applicable)

## GitHub Release

- [ ] Create git tag: `git tag -a v1.0.0 -m "VoiceBridge v1.0.0 — General Availability"`
- [ ] Push tag: `git push origin main --tags`
- [ ] Create GitHub Release from `GITHUB_RELEASE_BODY.md`
- [ ] Upload Docker image to GitHub Container Registry (ghcr.io)
- [ ] Generate and include SHA256 checksums in release body
- [ ] Set release as "Latest Release"
- [ ] Verify release page renders correctly

## Repository Settings

- [ ] Set repository to **Public** (if private)
- [ ] Enable **Discussions** (Settings → General → Features → Discussions)
- [ ] Enable **Sponsorships** (if applicable)
- [ ] Enable **Issues** (should already be enabled)
- [ ] Enable **Projects** (optional, for milestone tracking)
- [ ] Enable **Wiki** (optional, if using GitHub Wiki)
- [ ] Configure **Pages** (if hosting documentation site)
- [ ] Add repository topics: `mcp`, `voice-ai`, `agentcall`, `voicebridge`, `ai-communication`, `model-context-protocol`
- [ ] Add repository description (if not already set)
- [ ] Verify repository visibility in search results

## Project Website (Optional)

- [ ] Create landing page (GitHub Pages or Vercel)
- [ ] Domain: agentcall.dev or agentcall.io (if available)
- [ ] GitHub Pages: enable from `gh-pages` branch or `docs/` folder
- [ ] Add CNAME for custom domain (if using)
- [ ] SSL via Let's Encrypt or Caddy
- [ ] Landing page includes: hero, features, architecture, installation, docs link
- [ ] SEO: meta tags, Open Graph, Twitter cards
- [ ] Analytics: Plausible or Umami (privacy-focused)

## Documentation Site (Optional)

- [ ] Generate static documentation site (Docusaurus, VitePress, or mkdocs)
- [ ] Docs site structure:
  - Getting Started
  - Architecture
  - API Reference
  - Deployment
  - Operations
  - Contributing
- [ ] Search functionality enabled
- [ ] Version selector (v1.0.0)
- [ ] RSS feed for changelog updates
- [ ] Custom domain or subdomain (docs.agentcall.dev)

## Package Registry

- [ ] Publish `@agentcall/voicebridge` to npm (optional — backend is self-hosted)
- [ ] Publish `@agentcall/mcp-server` to npm
- [ ] Verify package names are not squatted
- [ ] npm package includes: README, LICENSE, CHANGELOG
- [ ] Docker image pushed to ghcr.io/agentcall/agentcall:1.0.0
- [ ] Docker image tagged as `:latest`
- [ ] Verify docker pull command works

## Social Announcement

- [ ] **X (Twitter):** Announcement thread with key features, link to GitHub
- [ ] **LinkedIn:** Professional announcement (if personal brand)
- [ ] **Hacker News:** "Show HN: AgentCall — Open-source MCP-based voice for AI"
- [ ] **Reddit:** r/MachineLearning, r/devops, r/selfhosted
- [ ] **Dev.to:** Technical blog post: "Building an AI Communication Platform"
- [ ] **MCP Community:** Discord / GitHub Discussions announcement
- [ ] **YouTube:** Demo video (2-3 min) — see Demo Video section

### Social Announcement Draft

> **AgentCall v1.0.0 is now open source!**
>
> AgentCall is an open, AI-agnostic communication platform that enables any AI (Claude, ChatGPT, Gemini, Cursor) to securely reach humans through voice calls.
>
> ✨ MCP Native — 8 built-in tools
> 📱 Android app with real-time voice
> 🏗 TypeScript strict, event-driven core
> 🚀 Docker Compose + Kubernetes ready
> 🔒 Privacy first — self-hosted, no cloud
>
> GitHub: https://github.com/agentcall/agentcall
> MIT licensed. Free. Always.

## Demo Video

- [ ] Length: 2-3 minutes
- [ ] Script:
  - 0:00-0:15 — Intro: "This is AgentCall — the communication platform for AI"
  - 0:15-0:45 — Backend startup: git clone, npm install, npm run dev
  - 0:45-1:15 — AI agent calls a human: MCP tool invocation
  - 1:15-1:45 — Android app receiving call: show phone ringing, answer, voice bridge
  - 1:45-2:00 — Transcript returned to AI agent
  - 2:00-2:15 — Complete call, cleanup
  - 2:15-2:30 — Wrap-up: "Open source, MIT, link in description"
- [ ] Tools: OBS for recording, DaVinci Resolve or iMovie for editing
- [ ] Upload to YouTube, add to README.md
- [ ] Add captions/subtitles

## Changelog Publication

- [ ] CHANGELOG.md already up to date
- [ ] Send changelog to:
  - GitHub Release (done above)
  - RSS feed (if documentation site has one)
  - Email newsletter (if applicable)

## Community Announcement

- [ ] GitHub Discussion: "Welcome to AgentCall!" pinned post
- [ ] GitHub Discussion: "v1.0.0 Release Discussion" for Q&A
- [ ] GitHub Discussion: "Show and Tell" for user deployments
- [ ] Respond to all comments and questions within 24 hours
- [ ] Monitor GitHub Issues for first bug reports
- [ ] Monitor first-time contributor PRs

## Post-Launch (24 hours after)

- [ ] Verify all social announcements are live and correct
- [ ] Check GitHub Insights for traffic spike
- [ ] Check first Google/Bing indexing
- [ ] Reply to all comments
- [ ] Triage first issues
- [ ] Review first PRs
- [ ] Post-launch retrospective: what went well, what could improve
- [ ] Update launch plan for v1.1 based on community feedback

## Post-Launch (1 week after)

- [ ] Run first security review of public-facing issues
- [ ] Evaluate community engagement metrics
- [ ] Adjust issue labels/templates based on first-week feedback
- [ ] Plan v1.0.1 patch release (if bugs found)
- [ ] Write "Lessons learned" blog post (optional)
