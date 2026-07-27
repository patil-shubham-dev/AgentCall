# Privacy & Compliance Document

> **HISTORICAL DESIGN DOCUMENT**
>
> This document describes the original design process.
> The implementation may differ.
> Refer to [ARCHITECTURE_BASELINE.md](../ARCHITECTURE_BASELINE.md) for the current architecture.
>
> **Canonical references:** [PRODUCT_VISION.md](../PRODUCT_VISION.md) (privacy-first philosophy) | [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md) (data ownership rules)

## AgentCall MCP

**Version:** 1.0
**Status:** Draft

---

## 1. Privacy Principles

```
Privacy-by-default → No user action required to be protected
Data minimization → Only collect what's strictly necessary
Transparency → Clear disclosure of what data is collected and why
User control → Users can view, export, and delete their data
Security → Encrypted in transit and at rest
```

---

## 2. Data Collection Inventory

### 2.1 Collected by Necessity

| Data | Purpose | Retention | Storage |
|------|---------|-----------|---------|
| Email address | Account identification, login | Until account deletion | PostgreSQL (encrypted at rest) |
| Display name | UI identification | Until account deletion | PostgreSQL |
| Device push token | Push notification delivery | Until device removed/revoked | PostgreSQL |
| Call metadata (timestamps, duration, participants) | Service operation, billing, analytics | 90 days | PostgreSQL |
| Call quality metrics (jitter, RTT, packet loss) | Service improvement, debugging | 180 days (aggregated) | PostgreSQL |
| IP address | Rate limiting, abuse prevention | 30 days (logs) | Log files |
| WebRTC audio | Real-time conversation | **Not stored** (default) | N/A |
| AI context sent to call | Enabling the call purpose | Until context returned to agent (max 5 min) | PostgreSQL (temporary) |

### 2.2 NOT Collected (By Design)

| Data | Reason |
|------|--------|
| Call recordings | Privacy-by-default: never recorded unless user explicitly opts in |
| Full conversation transcripts | Not retained; only structured summary returned to agent |
| Location data | Not needed for service operation |
| Contacts / address book | Not needed for service operation |
| Browsing history | Not collected |
| Biometric data | Not collected |
| Third-party data | Not purchased or imported |

---

## 3. Data Flow & Retention

### 3.1 Call Lifecycle Data Flow

```
Call Initiation:
  AI context (task_id, reason, summary)
    → Stored in PostgreSQL during call
    → Deleted after context returned to agent (or call timeout + 5min)
    → NOT used for training or analytics

During Call:
  WebRTC audio (SRTP encrypted)
    → Flows P2P or via TURN relay
    → TURN relay does NOT record or transcode
    → No server-side processing of audio content

Call End:
  Structured result (transcript_summary, user_response, decision)
    → Returned to AI agent via MCP resume_task
    → Retained in PostgreSQL for call history (90 days)
    → Full transcript ONLY retained if user explicitly opts in

Call Quality:
  Metrics (jitter, RTT, packet loss)
    → Stored for 180 days
    → Aggregated for service improvement
    → Not linkable to audio content
```

### 3.2 Retention Schedule

| Data Category | Default Retention | User Can Delete? | Maximum Retention |
|---------------|-------------------|------------------|-------------------|
| Account data | Indefinite (active account) | Yes | Deleted on account close |
| Call metadata | 90 days | Yes (individual calls) | 90 days |
| Quality metrics | 180 days | No (aggregated only) | 180 days |
| Auth logs | 30 days | No (security necessary) | 30 days |
| Push tokens | Until device removed | Yes | Deleted on device removal |
| Recordings (opt-in) | 30 days | Yes | 30 days |
| Transcripts (opt-in) | 30 days | Yes | 30 days |

---

## 4. User Privacy Controls

### 4.1 In-App Privacy Settings

```
Settings → Privacy

┌──────────────────────────────────────┐
│ Privacy                              │
│                                      │
│  Data Storage                        │
│  ┌──────────────────────────────────┐│
│  │ Store call recordings          ││
│  │ ○ Never (default)              ││
│  │ ● Ask each time               ││
│  │ ○ Always store                 ││
│  └──────────────────────────────────┘│
│                                      │
│  ┌──────────────────────────────────┐│
│  │ Store call transcripts          ││
│  │ ○ Never (default)              ││
│  │ ● Ask each time               ││
│  │ ○ Always store                 ││
│  └──────────────────────────────────┘│
│                                      │
│  Data Management                     │
│  ┌──────────────────────────────────┐│
│  │ [Export My Data]                ││
│  │ [Delete All Call History]       ││
│  │ [Delete Account]                ││
│  └──────────────────────────────────┘│
│                                      │
│  Call Data Usage                     │
│  ┌──────────────────────────────────┐│
│  │ ✗ Used for AI training          ││
│  │ ✗ Shared with third parties     ││
│  │ ✓ Used for service improvement  ││
│  └──────────────────────────────────┘│
└──────────────────────────────────────┘
```

### 4.2 User Rights

| Right | Implementation |
|-------|---------------|
| Right to be informed | This document + in-app privacy notice |
| Right to access | "Export My Data" generates JSON download of all user data |
| Right to rectification | Edit profile, delete individual call records |
| Right to erasure | "Delete Account" removes all data within 30 days |
| Right to restrict processing | Opt-out of recording/transcript storage |
| Right to data portability | Export in JSON format |
| Right to object | Opt-out of all non-essential processing |

---

## 5. Controlled Storage (User Opt-In)

When user enables recording or transcript storage:

### 5.1 Recording Encryption

```typescript
// Audio recording encryption
// AES-256-GCM with key derived from user's master key (zero-knowledge)

interface EncryptedRecording {
    iv: Buffer;          // 12-byte random IV
    ciphertext: Buffer;  // AES-256-GCM encrypted audio (Opus frames)
    authTag: Buffer;     // 16-byte GCM authentication tag
    salt: Buffer;        // 32-byte random salt for key derivation
    algorithm: "AES-256-GCM";
}

// Key derivation (server never has raw key):
// master_key = HKDF(user_password, salt, "agentcall-recording-key")
// file_key = HKDF(master_key, recording_id, "file-encryption-key")
```

Key management:
- Server never stores the raw encryption key
- Key derived from user's password on their device
- If user loses password, recordings are unrecoverable (zero-knowledge)
- Access logging: every decryption request logged to audit log

### 5.2 Access Controls

- Recordings only accessible by the owning user
- AI agents never receive raw recording
- Admin cannot access user recordings (technical enforcement via encryption)
- Access attempts logged: `audit_log` with `event_type = 'recording.accessed'`

---

## 6. Compliance Frameworks

### 6.1 GDPR (EU Users)

| Requirement | Implementation |
|-------------|---------------|
| Lawful basis for processing | Legitimate interest (service operation) + consent (recordings/transcripts) |
| Data Processing Agreement | Not applicable (no third-party processors beyond Hetzner + Firebase + Apple) |
| Data Protection Officer | Contact: privacy@agentcall.example.com |
| Breach notification | Within 72 hours to supervisory authority + affected users |
| Cross-border transfer | Data stored in EU (Hetzner Falkenstein, Germany) |
| Children's privacy | Service not intended for under-16. No knowingly collected data. |

### 6.2 CCPA (California Users)

| Requirement | Implementation |
|-------------|---------------|
| Right to know | Privacy policy + data export |
| Right to delete | Account deletion within 30 days |
| Right to opt-out | No sale of data, but opt-out form provided |
| Non-discrimination | Privacy choices do not affect service quality |
| Minor opt-in | Users under 16 must have parental consent |

### 6.3 SOC 2 (Future Goal)

- **Security:** Addressed in Security Architecture document
- **Availability:** 99.9% uptime target, monitoring in place
- **Processing Integrity:** Call state machine verified in tests
- **Confidentiality:** E2E encryption, access controls
- **Privacy:** This document

*Note: SOC 2 certification is a post-MVP initiative. Design decisions above lay the foundation.*

---

## 7. Third-Party Data Processing

| Processor | Data Shared | Purpose | Location | DPA |
|-----------|-------------|---------|----------|-----|
| Hetzner Cloud | All server-side data | Hosting infrastructure | Germany (Falkenstein) | ✓ Standard EU DPA |
| Firebase (Google) | FCM push token | Push notifications | Global (GCP) | ✓ Standard EU DPA |
| Apple | APNs push token, VoIP certificate | Push notifications | Global | ✓ Apple DPA |
| GitHub | Email (OAuth login) | Authentication | Global | ✓ GitHub DPA |
| Google | Email (OAuth login) | Authentication | Global | ✓ Google DPA |

**No other third parties have access to user data.** No analytics SDKs, no ad networks, no social media integrations.

---

## 8. Privacy Engineering Checklist

### 8.1 Code Review Gates

- [ ] All new data collection fields added to Data Inventory
- [ ] Retention period defined before storing new data type
- [ ] Any new third-party dependency reviewed for data access
- [ ] Opt-in required for any new non-essential data collection
- [ ] Audit logging added for data access events

### 8.2 Pre-Launch Verification

- [ ] Verify no call audio stored by default (TURN relay logs checked)
- [ ] Verify AI context auto-deleted after call completion
- [ ] Verify data export includes all user data
- [ ] Verify account deletion removes all data
- [ ] Verify recording encryption (if opt-in) — zero-knowledge test
- [ ] Privacy policy reviewed by legal (if available) or published prominently

---

## 9. Consent Management

```typescript
// Consent records stored in PostgreSQL
interface ConsentRecord {
    user_id: string;
    consent_type: 'recording' | 'transcript_storage' | 'analytics';
    granted: boolean;
    granted_at: string;  // ISO8601
    revoked_at?: string; // ISO8601 (null if still active)
    ip_address: string;
    user_agent: string;
}

// Every consent change logged to audit_log
// Consent revocation honored within 1 hour
// Re-consent required if policy changes materially
```

---

## 10. Incident Response for Privacy Breaches

### 10.1 Breach Types

| Type | Example | Severity |
|------|---------|----------|
| Audio leak | TURN relay logs captured audio | Critical |
| Metadata exposure | Call history DB leaked | High |
| Auth token theft | JWT signing key compromised | Critical |
| Push token abuse | Spam notifications | Medium |
| AI context leak | Call context accidentally persisted | High |

### 10.2 Breach Response Steps

1. **Identify & contain** (within 1 hour):
   - Identify affected systems and data
   - Isolate compromised services (network-level)
   - Rotate all secrets, revoke all tokens

2. **Assess & document** (within 4 hours):
   - Determine scope: what data, which users, how many
   - Determine root cause
   - Document timeline

3. **Notify** (within 72 hours for GDPR):
   - Affected users via email + in-app notification
   - Supervisory authority (if required)
   - Detail: what happened, what data, what we've done, next steps

4. **Remediate & report** (within 7 days):
   - Fix root cause
   - Enhance monitoring
   - Post-mortem published (internally)
   - Report to regulator if required

---

## 11. Privacy Policy (Summary for In-App Display)

> **AgentCall MCP Privacy Policy (Summary)**
>
> We only collect what's needed to make calls work: your email, device info, and call timestamps. We never record your calls or store transcripts unless you explicitly turn that on. Your call audio is encrypted end-to-end and we can't listen to it. We don't sell your data, use it for training AI, or share it with advertisers. You can export or delete your data at any time. Full policy at [URL].
