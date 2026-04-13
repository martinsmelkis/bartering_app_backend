## Privacy Policy

**Effective date:** 2026-04-13  
**Last updated:** 2026-04-13

This Privacy Policy explains how Barter processes personal data through its backend services and connected client applications.

## 1. Controller and Contact

- **Controller:** [Add legal entity / owner name]
- **Address:** [Add legal address]
- **Privacy contact:** [Add privacy email]
- **Data Protection Officer (if appointed):** [Add DPO contact]

## 2. Scope and System Components

This policy covers processing in:
- backend APIs,
- connected client apps (mobile/web),
- admin/compliance dashboards,
- optional federation features (if enabled).

## 3. Data We Process

We may process:
- **Account and authentication data**: user ID, device/public key metadata, signature verification metadata.
- **Profile data**: display name, optional coordinates, profile attributes/interests/offers, profile media URLs.
- **Content and communication data**: postings, chat/offline message data, encrypted file metadata, review/reputation records.
- **Notification data**: email address, push tokens, notification preferences (including consent flags).
- **Security/compliance data**: audit events, DSAR/erasure/legal-hold records, incident-management records.
- **Technical metadata**: timestamps, request metadata, IP/device hashes for security and accountability.

## 4. Purposes and GDPR Legal Bases

We process data for:
- **Service delivery** (Art. 6(1)(b)): account, posting, matching, messaging, notifications.
- **Security and abuse prevention** (Art. 6(1)(f)): signature checks, anti-fraud/risk controls, operational safety.
- **Compliance/accountability** (Art. 6(1)(c) and 6(1)(f)): audit trails, legal hold enforcement, DSAR management, incident handling.
- **Consent-based processing** (Art. 6(1)(a)): optional consents (for example, location/federation/notification consent settings where applicable).

## 5. SDKs, Processors, and Infrastructure Actually Used

Current backend integrations include:
- **PostgreSQL** (primary data storage).
- **Mailjet** (email delivery).
- **Firebase Admin / FCM** (push notifications, if configured).
- **Ollama** (local/self-hosted model service for embeddings/search support).
- **Nginx + Docker hosting stack** (deployment/runtime infrastructure).
- **Optional federation peers** (only when federation is enabled and trusted).

If a provider acts as a processor, processing is based on a relevant data processing agreement and required safeguards.

## 6. Retention and Deletion

The backend implements retention controls and scheduled cleanup, including:
- configurable retention windows for chat, analytics, read receipts, and risk-tracking data,
- cleanup of old compliance-operational records,
- DSAR request lifecycle retention,
- legal-hold-aware retention (held user data excluded from certain purge actions).

Retention periods are configured operationally and reviewed as part of governance.

## 7. Account Deletion and Right to Erasure

Users can request account deletion through authenticated flows.

Deletion workflow includes:
- DSAR request creation and status tracking,
- legal hold checks,
- deletion/cascade cleanup across linked data domains,
- follow-up erasure task registration for additional storage scopes where needed.

## 8. Data Export and Right to Portability

Users can request data export via authenticated flows.

Export workflow includes:
- DSAR tracking,
- legal hold checks,
- export artifact generation (machine-readable JSON in ZIP),
- export dispatch to configured contact email,
- compliance event logging for request and completion.

## 9. Consent Management

Consent-related fields are stored and updated with timestamp/version metadata where applicable (e.g., privacy policy/terms acceptance versions, location/federation/other consent flags).

Consent updates are logged for accountability.

## 10. Security Measures

We apply technical and organizational measures such as:
- authenticated request-signature verification,
- access control for privileged/compliance routes,
- transport security in deployed environments (TLS via reverse proxy),
- compliance/security audit logging,
- structured retention and deletion workflows.

No system is perfectly secure, but controls are continuously improved.

## 11. International Data Transfers

Depending on deployment and chosen providers, data may be processed outside the EEA/your country. Where required, transfers rely on legally valid safeguards (for example SCCs or equivalent mechanisms).

## 12. Children

The service is not intended for children below the minimum lawful age in applicable jurisdictions. If unlawful child data processing is identified, contact us for remediation/removal.

## 13. Your GDPR Rights

Subject to law, you may request:
- access,
- rectification,
- erasure,
- restriction,
- portability,
- objection,
- withdrawal of consent (where consent is the legal basis).

You may also lodge a complaint with your supervisory authority.

## 14. Changes to This Policy

We may update this policy from time to time. Material changes should be communicated in-app or through another appropriate channel with updated effective dates.

## 15. Contact

For privacy and GDPR requests, contact: **[Add privacy email]**.
