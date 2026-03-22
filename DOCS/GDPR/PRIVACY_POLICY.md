## Privacy Policy

**Effective date:** 2026-03-19  
**Last updated:** 2026-03-19

This Privacy Policy explains how the Barter App Backend ("Service") processes personal data in connection with barter, messaging, profile discovery, notifications, and federation features.

By using the Service, you acknowledge this Privacy Policy.

## 1. Controller and Contact

- **Controller:** [Add legal entity / individual owner]
- **Address:** [Add postal address]
- **Email:** [Add privacy contact email]
- **Data Protection Contact (if applicable):** [Add DPO/contact]

## 2. Scope

This Privacy Policy applies to personal data processed through:
- account registration and authentication,
- profile and attribute management,
- posting and search features,
- messaging and encrypted file exchange,
- review/reputation systems,
- notifications (email/push),
- optional federation between trusted servers.

## 3. Categories of Personal Data

Depending on usage, we may process:
- **Identity/account data:** user ID, public key, device key metadata.
- **Profile data:** display name, location coordinates, attributes/interests/offers.
- **Content data:** postings, images, encrypted files metadata, chat/offline messages.
- **Interaction data:** relationships (connections/blocks), reviews, reputation, transactions.
- **Technical/activity data:** timestamps, presence/last activity, security logs, rate-limit/security events.
- **Notification data:** email contact and push/contact tokens, preference settings.
- **Federation data (if enabled):** trusted-server metadata, federated profile/posting cache, federation audit logs.

## 4. Purposes and Legal Bases (GDPR Art. 6)

We process data for these purposes:
- **Service performance** (Art. 6(1)(b)): account operation, matching, messaging, posting, and profile discovery.
- **Security and abuse prevention** (Art. 6(1)(f)): signature verification, replay protection, fraud/risk controls, system integrity.
- **Legitimate interests** (Art. 6(1)(f)): improve relevance, inactivity management, reliability, and platform safety.
- **Consent** (Art. 6(1)(a), where required): optional notifications/communications and similar opt-in processing.
- **Legal obligations** (Art. 6(1)(c), where applicable): compliance, legal requests, and required records.

## 5. Data Retention

We keep personal data only as long as needed for the above purposes.

Current operational behavior includes:
- **User-initiated deletion:** permanent deletion workflow across user-related data domains.
- **Inactive account lifecycle:** active/inactive/dormant states; optional auto-delete can be enabled by configuration (default disabled).
- **Logs and operational records:** retained for security, debugging, and compliance needs for a limited period.

If legal obligations require longer retention for specific records, those records are retained only as required.

## 6. Account Deletion and Right to Erasure

You can request deletion via the authenticated account deletion endpoint. Deletion is designed to be permanent and includes direct and cascade cleanup across core profile, relationship, posting, messaging, and reputation-linked data structures, plus cache cleanup and associated media cleanup flows.

## 7. Recipients and Processors

Data may be processed by:
- infrastructure and hosting providers,
- email/push service providers,
- storage providers for uploaded content,
- analytics/security tooling used for operation and abuse prevention,
- federation peers (only if federation is enabled and configured).

Where providers act as processors, appropriate contractual safeguards should be in place.

## 8. International Transfers

If providers or federation peers are outside your jurisdiction/EEA, personal data may be transferred internationally. Where required, transfers should rely on valid safeguards (e.g., SCCs or equivalent legal mechanisms).

## 9. Data Subject Rights (GDPR)

Subject to legal conditions, you may request:
- access,
- rectification,
- erasure,
- restriction,
- portability,
- objection,
- withdrawal of consent (where processing is based on consent).

You also have the right to lodge a complaint with your supervisory authority.

## 10. Security Measures

We apply technical and organizational measures, including:
- request signature verification,
- timestamp checks to reduce replay attacks,
- authenticated route controls,
- encrypted transport (TLS in deployment),
- controlled data access and deletion workflows,
- monitoring and audit-related logs.

No method of transmission/storage is 100% secure, but we continuously improve safeguards.

## 11. Children

The Service is not intended for children under the age required by applicable law in your jurisdiction. If you believe a child has provided personal data unlawfully, contact us for removal.

## 12. Cookies / Tracking

If frontends or integrated clients use cookies or similar identifiers, they must provide corresponding notices/controls. Backend operational logs may still process technical request metadata for security and reliability.

## 13. Changes to this Policy

We may update this Privacy Policy. Material changes should be communicated through the app/site or another appropriate channel, with updated effective dates.

## 14. Contact

For privacy requests and GDPR rights, contact: **[Add privacy email]**.
