# Repository Documentation Structure

## Proposed Directory Layout

```
/
├── README.md                          # Project readme
├── CHANGELOG.md                       # Release history
├── VERSION.md                         # Version metadata
├── CONTRIBUTING.md                    # Contributor guide
├── CODE_OF_CONDUCT.md                 # Code of conduct
├── COMMUNITY.md                       # Community info
├── SUPPORT.md                         # Support info
├── SECURITY.md                        # Security policy
├── AGENTS.md                          # OpenCode agent config
│
├── API_SPEC.md                        # API contract (public)
├── ARCHITECTURE.md                    # Architecture overview (public)
├── ARCHITECTURE_BASELINE.md           # Locked architecture baseline (public)
├── DATABASE_GUIDE.md                  # Database operations (public)
├── DEPLOYMENT_GUIDE.md                # Deployment procedures (public)
├── DEVELOPMENT_GUIDE.md              # Dev setup guide (public)
├── KNOWN_LIMITATIONS.md               # Known limitations (public)
├── OPERATIONS_BASELINE.md             # SLO targets (public)
├── PRODUCTION_READINESS.md            # Production readiness (public)
├── PRODUCT_VISION.md                  # Product vision (public)
├── PROJECT_OVERVIEW.md               # Project overview (public)
├── RELEASE_NOTES_v1.0.md              # Release notes (public)
├── ROADMAP.md                         # Roadmap (public)
├── SYSTEM_ARCHITECTURE.md             # System architecture spec (public)
├── VOICEBRIDGE_V1_GA.md              # GA declaration (public)
├── VOICEBRIDGE_V1_FINAL.md           # Engineering handoff (public)
│
├── docs/
│   ├── AI_INTEGRATION.md
│   ├── API_GUIDELINES.md
│   ├── ARCHITECTURE_CHECKLIST.md
│   ├── CHATGPT_INTEGRATION.md
│   ├── CODE_OWNERSHIP.md
│   ├── CODE_STYLE.md
│   ├── CURRENT_STACK.md
│   ├── DISASTER_RECOVERY.md
│   ├── ERROR_HANDLING.md
│   ├── FREE_ARCHITECTURE.md
│   ├── GRAFANA_DASHBOARDS.md
│   ├── IMPLEMENTATION_ROADMAP.md
│   ├── IMPLEMENTATION_RULES.md
│   ├── INFRASTRUCTURE.md
│   ├── LOGGING_GUIDE.md
│   ├── MULTI_PROVIDER_PLAN.md
│   ├── OPEN_SOURCE_READINESS.md
│   ├── PERFORMANCE_GUIDELINES.md
│   ├── PERSISTENCE_ARCHITECTURE.md
│   ├── PRD.md
│   ├── REPOSITORY_CLEANUP.md
│   ├── RISK_REGISTER.md
│   ├── SCALABILITY_GUIDE.md
│   ├── SECURITY_GUIDELINES.md
│   ├── SESSION_LIFECYCLE_POLICY.md
│   ├── TECHNICAL_DEBT_REGISTER_v1.md
│   ├── TESTING_GUIDE.md
│   │
│   ├── 01-architecture-design.md       (design docs, permanent)
│   ├── 02-api-protocol-specification.md
│   ├── 03-database-schema.md
│   ├── 04-security-architecture.md
│   ├── 05-mobile-app-technical-spec.md
│   ├── 06-ui-ux-wireframes.md
│   ├── 07-mvp-scope-milestone-plan.md
│   ├── 08-testing-qa-strategy.md
│   ├── 09-infrastructure-cicd-plan.md
│   ├── 10-privacy-compliance.md
│   │
│   ├── adr/
│   │   ├── 0001-system-philosophy.md
│   │   └── ... (10 ADRs total, permanent)
│   │
│   ├── archive/
│   │   ├── DEAD_CODE_AUDIT.md
│   │   ├── EVENT_BUS_DESIGN.md
│   │   ├── EVENT_BUS_HARDENING_REPORT.md
│   │   ├── EVENT_BUS_REVIEW.md
│   │   ├── FINAL_ARCHITECTURE_AUDIT.md
│   │   ├── FINAL_CONCURRENCY_REVIEW.md
│   │   ├── FINAL_COST_ANALYSIS.md
│   │   ├── FINAL_DATABASE_REVIEW.md
│   │   ├── FINAL_MEMORY_REVIEW.md
│   │   ├── FINAL_OPERATIONAL_REVIEW.md
│   │   ├── FINAL_PRODUCTION_CERTIFICATION.md
│   │   ├── FINAL_RUNTIME_REVIEW.md
│   │   ├── FINAL_SECURITY_REVIEW.md
│   │   ├── FINAL_TECH_DEBT.md
│   │   ├── IMPLEMENTATION_PLAN.md
│   │   ├── IMPLEMENTATION_READINESS.md
│   │   ├── IMPLEMENTATION_SEQUENCE.md
│   │   ├── MODULE_DEPENDENCY_REPORT.md
│   │   ├── PHASE0_VALIDATION_REPORT.md
│   │   ├── PHASE1A_REPORT.md
│   │   ├── PHASE2_1_REPORT.md
│   │   ├── PHASE2_2A_REPORT.md
│   │   ├── PHASE2_2B_REPORT.md
│   │   ├── PHASE2_2C_REPORT.md
│   │   ├── PHASE2_3_REPORT.md
│   │   ├── PHASE2_4_REPORT.md
│   │   ├── PHASE2_5A_SESSION_AUDIT.md
│   │   ├── PHASE2_5C_REPORT.md
│   │   ├── PHASE2_MIGRATION_REVIEW.md
│   │   ├── PHASE3_0_REPORT.md
│   │   ├── PHASE3_1_REPORT.md
│   │   ├── PHASE3_2_REPORT.md
│   │   ├── PHASE3_3_REPORT.md
│   │   ├── PHASE3_4_REPORT.md
│   │   ├── PHASE4_1_REPORT.md
│   │   ├── PHASE4_2_REPORT.md
│   │   ├── PHASE4_3_REPORT.md
│   │   ├── PHASE4_4_REPORT.md
│   │   ├── PHASE4_5_REPORT.md
│   │   ├── PHASE4_6_REPORT.md
│   │   ├── PHASE4_7_REPORT.md
│   │   ├── PHASE4_8_REPORT.md
│   │   ├── PHASE4_9_REPORT.md
│   │   ├── PHASE5_REPORT.md
│   │   ├── RC2_FIX_REPORT.md
│   │   ├── RC2_RELEASE_RECOMMENDATION.md
│   │   ├── REALITY_AUDIT.md
│   │   ├── REFACTOR_PLAN.md
│   │   ├── SECURITY_AUDIT.md
│   │   ├── TECHNICAL_DEBT.md
│   │   └── ARCHITECTURE_COMPLIANCE_REPORT.md
│   │
│   └── reports/
│       ├── android-verification-report.md
│       ├── CANARY_REPORT.md
│       ├── CHAOS_TEST_REPORT.md
│       ├── CHAOS_VALIDATION_REPORT.md
│       ├── CI_RELEASE_REPORT.md
│       ├── DATABASE_VALIDATION_REPORT.md
│       ├── DOCUMENTATION_ALIGNMENT_REPORT.md
│       ├── DOCUMENTATION_CONSISTENCY_REPORT.md
│       ├── docs/DOCUMENTATION_MIGRATION_REPORT.md → docs/reports/DOCUMENTATION_MIGRATION_REPORT.md
│       ├── E2E_VALIDATION_REPORT.md
│       ├── EVENT_BUS_VALIDATION.md
│       ├── FAILURE_INJECTION_REPORT.md
│       ├── FINAL_GO_LIVE_CHECKLIST.md
│       ├── FINAL_RELEASE_GATE.md
│       ├── FINAL_RELEASE_PACKAGE.md
│       ├── FINAL_RELEASE_RECOMMENDATION.md
│       ├── FINAL_RISK_REGISTER.md
│       ├── LOAD_TEST_REPORT.md
│       ├── MONITORING_VALIDATION_REPORT.md
│       ├── OBSERVABILITY_VALIDATION.md
│       ├── OPERATIONS_VALIDATION_REPORT.md
│       ├── PERFORMANCE_BASELINE.md
│       ├── PERFORMANCE_REPORT.md
│       ├── PRODUCTION_OBSERVATION_REPORT.md
│       ├── PRODUCTION_ROLLOUT_REPORT.md
│       ├── PRODUCTION_VALIDATION_REPORT.md
│       ├── REAL_DEPLOYMENT_REPORT.md
│       ├── RELEASE_ARTIFACTS.md
│       ├── SECURITY_VALIDATION_REPORT.md
│       ├── SMOKE_TEST_RESULTS.md
│       ├── STABILITY_TEST_REPORT.md
│       ├── STAGING_DEPLOYMENT_REPORT.md
│       └── STAGING_SIGNOFF.md
│
└── .github/
    ├── ISSUE_TEMPLATE/
    │   ├── bug_report.md
    │   └── feature_request.md
    ├── PROJECT_PLAN.md
    └── PULL_REQUEST_TEMPLATE.md
```

## Move Summary by Category

| Category | Count | Destination |
|----------|-------|-------------|
| Root → docs/ | 28 | Maintainer documentation |
| Root → docs/archive/ | 42 | Historical engineering + internal audits |
| Root → docs/reports/ | 30 | Validation reports |
| docs/ → docs/reports/ | 1 | DOCUMENTATION_MIGRATION_REPORT.md |
| Stay in root | 26 | Public documentation |
| Stay in .github/ | 4 | GitHub templates and project plan |
| Stay in docs/ | 12 | Design documents (01-*) + ADRs |
