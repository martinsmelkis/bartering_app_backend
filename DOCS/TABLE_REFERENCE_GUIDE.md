# Complete Table Reference Guide

## Table Listing & Usage Index

All 57 Exposed table definitions with usage evidence and confidence levels.

---

## CORE TABLES (Critical to Operations)

### UserRegistrationDataTable
- **File**: `src/app/bartering/features/profile/db/UserRegistrationDataTable.kt`
- **Purpose**: Master user authentication & registration data
- **Usage Level**: CRITICAL
- **References**: 38+ files
- **Key Operations**: Select, Insert, Delete (cascade deletes all user data)
- **Evidence**: AuthenticationDaoImpl, all feature DAOs, UserActivityCache
- **Confidence**: VERY HIGH

### UserProfilesTable
- **File**: `src/app/bartering/features/profile/db/UserProfilesTable.kt`
- **Purpose**: User profile details (name, avatar, description, location)
- **Usage Level**: HIGH
- **References**: 15+ files
- **Key Operations**: Select, Update, Insert
- **Evidence**: UserProfileDaoImpl (primary), profile routes, matching queries
- **Confidence**: VERY HIGH

### UserProfileDaoImpl
- **File**: `src/app/bartering/features/profile/dao/UserProfileDaoImpl.kt`
- **Purpose**: Semantic search and profile matching operations
- **Dependencies**: UserSemanticProfilesTable (34+ usages), UserAttributesTable
- **Key Operations**: Semantic embedding storage, profile hash computation, matching
- **Evidence**: Used by profile matching algorithm
- **Confidence**: VERY HIGH

### UserAttributesTable
- **File**: `src/app/bartering/features/attributes/db/UserAttributesTable.kt`
- **Purpose**: User attributes linked to master categories
- **Usage Level**: HIGH
- **Key Operations**: Select, Insert, Update, Delete
- **Evidence**: UserAttributesDaoImpl, profile matching, search
- **Confidence**: VERY HIGH

### UserPrivacyConsentsTable
- **File**: `src/app/bartering/features/profile/db/UserPrivacyConsentsTable.kt`
- **Purpose**: GDPR consent tracking (location, AI processing, analytics, federation)
- **Usage Level**: HIGH
- **Key Operations**: Select, Update, Insert
- **Evidence**: UserProfileDaoImpl, compliance tracking
- **Confidence**: VERY HIGH

### UserRegistrationDataTable
- **File**: `src/app/bartering/features/profile/db/UserRegistrationDataTable.kt`
- **Purpose**: User authentication credentials & basic info
- **Usage Level**: CRITICAL
- **Key Operations**: All CRUD, primary key for cascade deletes
- **Evidence**: Referenced in 38+ files
- **Confidence**: VERY HIGH

---

## REVIEW & REPUTATION SYSTEM

### ReviewsTable
- **File**: `src/app/bartering/features/reviews/db/ReviewsTable.kt`
- **Purpose**: Store user reviews after completed transactions
- **Usage Level**: HIGH
- **Key Operations**: Insert (new reviews), Select (user profile display), Update (visibility/moderation)
- **FK Dependencies**: BarterTransactionsTable
- **Evidence**: ReviewDaoImpl, user deletion cleanup
- **Confidence**: VERY HIGH

### BarterTransactionsTable
- **File**: `src/app/bartering/features/reviews/db/BarterTransactionsTable.kt`
- **Purpose**: Track all bartering transactions between users
- **Usage Level**: HIGH
- **Key Operations**: Insert (new transaction), Update (completion status), Select (history)
- **Evidence**: ReviewsTable FK, transaction lifecycle
- **Confidence**: VERY HIGH

### ReputationsTable
- **File**: `src/app/bartering/features/reviews/db/ReputationsTable.kt`
- **Purpose**: Aggregated reputation scores (average rating, review count, trust level)
- **Usage Level**: HIGH
- **Key Operations**: Update (after review), Select (user profile)
- **Evidence**: ReputationDaoImpl, review aggregation
- **Confidence**: VERY HIGH

### ReputationBadgesTable
- **File**: `src/app/bartering/features/reviews/db/ReputationBadgesTable.kt`
- **Purpose**: User achievement badges (milestones, special recognitions)
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (achievement unlock), Select (profile display)
- **Evidence**: ReputationDaoImpl
- **Confidence**: HIGH

### ReviewAppealsTable
- **File**: `src/app/bartering/features/reviews/db/ReviewAppealsTable.kt`
- **Purpose**: Appeal mechanism for disputed reviews
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (new appeal), Update (resolution), Select (moderation queue)
- **Evidence**: ReviewDaoImpl, user deletion (delete orphaned appeals)
- **Confidence**: HIGH

### ModerationQueueTable
- **File**: `src/app/bartering/features/reviews/db/ModerationQueueTable.kt`
- **Purpose**: Queue system for content moderation decisions
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (flag for review), Update (decision), Select (queue listing)
- **Evidence**: ReviewDaoImpl
- **Confidence**: HIGH

### ReviewAuditLogTable
- **File**: `src/app/bartering/features/reviews/db/ReviewAuditLogTable.kt`
- **Purpose**: Audit trail of all review actions and changes
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (audit entry), Select (historical queries)
- **Evidence**: ReviewDaoImpl, RiskPatternDaoImpl
- **Confidence**: HIGH

### RiskPatternsTable
- **File**: `src/app/bartering/features/reviews/db/RiskPatternsTable.kt`
- **Purpose**: Store detected suspicious patterns (wash trading, device sharing, etc.)
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (detect pattern), Update (resolution), Select (investigation)
- **Evidence**: RiskPatternDaoImpl
- **Confidence**: HIGH

---

## TRACKING TABLES (Fraud Detection)

### DeviceTrackingTable
- **File**: `src/app/bartering/features/reviews/db/DeviceTrackingTable.kt`
- **Purpose**: Device fingerprinting for fraud detection
- **Usage Level**: LOW (only in deletion)
- **Key Operations**: Delete (user cleanup)
- **References**: AuthenticationDaoImpl (line 498)
- **Status**: ⚠️ Imported but no active population found
- **Confidence**: MEDIUM (appears incomplete)

### IpTrackingTable
- **File**: `src/app/bartering/features/reviews/db/IpTrackingTable.kt`
- **Purpose**: IP address tracking for abuse prevention
- **Usage Level**: LOW (only in deletion)
- **Key Operations**: Delete (user cleanup)
- **References**: AuthenticationDaoImpl (line 503)
- **Status**: ⚠️ Imported but no active population found
- **Confidence**: MEDIUM (appears incomplete)

### UserLocationChangesTable
- **File**: `src/app/bartering/features/reviews/db/UserLocationChangesTable.kt`
- **Purpose**: Location history for detecting suspicious movement patterns
- **Usage Level**: LOW (only in deletion)
- **Key Operations**: Delete (user cleanup)
- **References**: AuthenticationDaoImpl (line 508)
- **Status**: ⚠️ Imported but no active population found
- **Confidence**: MEDIUM (appears incomplete)

---

## WALLET & LEDGER SYSTEM

### WalletsTable
- **File**: `src/app/bartering/features/wallet/db/WalletsTable.kt`
- **Purpose**: User wallet balance and transaction status
- **Usage Level**: HIGH
- **Key Operations**: Insert (new wallet), Update (balance), Select (balance inquiry)
- **Evidence**: WalletDaoImpl
- **Confidence**: VERY HIGH

### LedgerEntriesTable
- **File**: `src/app/bartering/features/wallet/db/LedgerEntriesTable.kt`
- **Purpose**: Fine-grained transaction log (every credit/debit)
- **Usage Level**: HIGH
- **Key Operations**: Insert (log entry), Select (transaction history)
- **Evidence**: WalletDaoImpl
- **Confidence**: VERY HIGH

### LedgerTransactionsTable
- **File**: `src/app/bartering/features/wallet/db/LedgerTransactionsTable.kt`
- **Purpose**: Grouped transaction records for reporting
- **Usage Level**: HIGH
- **Key Operations**: Insert (batch record), Select (reporting)
- **Evidence**: WalletDaoImpl, UserActivityRewardService
- **Confidence**: VERY HIGH

### UserActivityRewardProgressTable
- **File**: `src/app/bartering/features/wallet/db/UserActivityRewardProgressTable.kt`
- **Purpose**: Track user progress toward reward thresholds
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (milestone tracking), Update (progress), Select (status)
- **Evidence**: UserActivityRewardService (5 file references)
- **Confidence**: HIGH

---

## PURCHASES & SUBSCRIPTIONS

### UserPurchasesTable
- **File**: `src/app/bartering/features/purchases/db/UserPurchasesTable.kt`
- **Purpose**: Record of premium purchases (for analytics/history)
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (purchase record), Select (history)
- **Evidence**: PurchasesDaoImpl, FK to UserPremiumEntitlementsTable
- **Confidence**: HIGH

### UserPremiumEntitlementsTable
- **File**: `src/app/bartering/features/purchases/db/UserPremiumEntitlementsTable.kt`
- **Purpose**: Active premium subscription status and expiration
- **Usage Level**: HIGH
- **Key Operations**: Update (grant/revoke subscription), Select (subscription check)
- **Evidence**: PurchasesDaoImpl, RevenueCat integration
- **Confidence**: VERY HIGH

### RevenueCatProcessedEventsTable
- **File**: `src/app/bartering/features/purchases/db/RevenueCatProcessedEventsTable.kt`
- **Purpose**: Deduplication log for Revenue Cat webhook events
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (processed event), Select (dedup check)
- **Evidence**: PurchasesDaoImpl
- **Confidence**: HIGH

---

## COMPLIANCE & GOVERNANCE

### ComplianceSecurityIncidentsTable
- **File**: `src/app/bartering/features/compliance/db/ComplianceSecurityIncidentsTable.kt`
- **Purpose**: Log security incidents for audit and response
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (incident report), Select (investigation), Update (resolution)
- **Evidence**: SecurityIncidentService
- **Confidence**: HIGH

### ComplianceSecurityIncidentUsersTable
- **File**: `src/app/bartering/features/compliance/db/ComplianceSecurityIncidentUsersTable.kt`
- **Purpose**: Track users affected by security incidents
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (affected user), Select (impact analysis)
- **Evidence**: SecurityIncidentService
- **Confidence**: HIGH

### ComplianceAuditLogTable
- **File**: `src/app/bartering/features/compliance/db/ComplianceAuditLogTable.kt`
- **Purpose**: GDPR audit trail for all sensitive operations
- **Usage Level**: HIGH
- **Key Operations**: Insert (audit entry), Select (compliance reporting)
- **Evidence**: ComplianceAuditService
- **Confidence**: HIGH

### ComplianceErasureTasksTable
- **File**: `src/app/bartering/features/compliance/db/ComplianceErasureTasksTable.kt`
- **Purpose**: Track data erasure requests (GDPR Right-to-be-Forgotten)
- **Usage Level**: HIGH
- **Key Operations**: Insert (erasure request), Update (status), Select (processing queue)
- **Evidence**: ErasureTaskService
- **Confidence**: VERY HIGH

### DataSubjectRequestsTable
- **File**: `src/app/bartering/features/compliance/db/DataSubjectRequestsTable.kt`
- **Purpose**: Data Subject Access Request (DSAR) management
- **Usage Level**: HIGH
- **Key Operations**: Insert (DSAR request), Update (fulfillment), Select (queue)
- **Evidence**: DataSubjectRequestService
- **Confidence**: VERY HIGH

### ComplianceRetentionPolicyRegisterTable
- **File**: `src/app/bartering/features/compliance/db/ComplianceRetentionPolicyRegisterTable.kt`
- **Purpose**: Data retention policy registry (GDPR mandatory documentation)
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (policy), Select (audit), Update (version)
- **Evidence**: ComplianceGovernanceService
- **Confidence**: HIGH

### ComplianceRopaRegisterTable
- **File**: `src/app/bartering/features/compliance/db/ComplianceRopaRegisterTable.kt`
- **Purpose**: Records of Processing Activities (GDPR mandatory documentation)
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (processing activity), Select (audit), Update (version)
- **Evidence**: ComplianceGovernanceService
- **Confidence**: HIGH

### LegalHoldsTable
- **File**: `src/app/bartering/features/compliance/db/LegalHoldsTable.kt`
- **Purpose**: Legal hold preservation orders (for litigation/investigation)
- **Usage Level**: LOW
- **Key Operations**: Insert (hold order), Select (compliance check), Update (status)
- **Evidence**: LegalHoldService
- **Confidence**: HIGH

---

## NOTIFICATIONS & PREFERENCES

### MatchHistoryTable
- **File**: `src/app/bartering/features/notifications/db/MatchHistoryTable.kt`
- **Purpose**: Store user match recommendations for notification & tracking
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (new match), Update (viewed/dismissed), Select (notification queue)
- **Evidence**: NotificationPreferencesDaoImpl (45+ usages), MatchNotificationService
- **Confidence**: HIGH

### PostingNotificationPreferencesTable
- **File**: `src/app/bartering/features/notifications/db/PostingNotificationPreferencesTable.kt`
- **Purpose**: Per-listing notification settings
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (preference), Update (settings), Select (delivery decision)
- **Evidence**: NotificationPreferencesDaoImpl
- **Confidence**: HIGH

### AttributeNotificationPreferencesTable
- **File**: `src/app/bartering/features/notifications/db/AttributeNotificationPreferencesTable.kt`
- **Purpose**: Per-attribute-category notification preferences
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (preference), Update (settings), Select (delivery decision)
- **Evidence**: NotificationPreferencesDaoImpl
- **Confidence**: HIGH

### UserNotificationContactsTable
- **File**: `src/app/bartering/features/notifications/db/NotificationContactsTable.kt`
- **Purpose**: Notification delivery contact methods (email, push, etc.)
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (contact), Update (verification), Select (delivery)
- **Evidence**: NotificationPreferencesDaoImpl, user deletion
- **Confidence**: HIGH

---

## CHAT & MESSAGING

### ReadReceiptsTable
- **File**: `src/app/bartering/features/chat/db/ReadReceiptsTable.kt`
- **Purpose**: Message read confirmation tracking
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (receipt), Select (read status), Update (timestamp)
- **Evidence**: ReadReceiptDao, user deletion
- **Confidence**: HIGH

### OfflineMessagesTable
- **File**: `src/app/bartering/features/chat/db/OfflineMessagesTable.kt`
- **Purpose**: Queue of messages for offline users
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (queued message), Delete (delivered), Select (queue)
- **Evidence**: OfflineMessageDaoImpl, user deletion
- **Confidence**: HIGH

### EncryptedFilesTable
- **File**: `src/app/bartering/features/encryptedfiles/db/EncryptedFilesTable.kt`
- **Purpose**: End-to-end encrypted file metadata & storage references
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (uploaded file), Select (file list), Delete (cleanup)
- **Evidence**: EncryptedFileDaoImpl, user deletion
- **Confidence**: HIGH

### ChatResponseTimesTable
- **File**: `src/app/bartering/features/chat/db/ChatResponseTimesTable.kt`
- **Purpose**: Analytics on message response times between users
- **Usage Level**: LOW (analytics only)
- **Key Operations**: Insert (metric), Select (reporting), Delete (cleanup)
- **Evidence**: ChatAnalyticsDaoImpl, user deletion
- **Confidence**: HIGH

---

## RELATIONSHIP MANAGEMENT

### UserRelationshipsTable
- **File**: `src/app/bartering/features/relationships/db/UserRelationshipsTable.kt`
- **Purpose**: User-to-user relationships (blocks, friends, etc.)
- **Usage Level**: HIGH
- **Key Operations**: Insert (relationship), Delete (unblock), Select (check)
- **Evidence**: UserRelationshipsDaoImpl, 38+ file references
- **Confidence**: VERY HIGH

### UserReportsTable
- **File**: `src/app/bartering/features/relationships/db/UserReportsTable.kt`
- **Purpose**: User-to-user abuse/safety reports
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (report), Select (moderation queue), Update (resolution)
- **Evidence**: UserReportsDaoImpl, moderation workflows
- **Confidence**: HIGH

---

## USER PRESENCE & ACTIVITY

### UserPresenceTable
- **File**: `src/app/bartering/features/profile/db/UserPresenceTable.kt`
- **Purpose**: Track user online/offline status for real-time features
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (login), Update (activity), Select (presence check)
- **Evidence**: UserActivityFilter, UserActivityRewardService, InactiveUserCleanupTask
- **Confidence**: HIGH

### UserDailyActivityStatsTable
- **File**: `src/app/bartering/features/analytics/db/UserDailyActivityStatsTable.kt`
- **Purpose**: Aggregated daily activity metrics (anonymized)
- **Usage Level**: MEDIUM (analytics only)
- **Key Operations**: Insert (daily record), Select (analytics reporting)
- **Evidence**: UserDailyActivityStatsDaoImpl
- **Status**: Marked with @Suppress("unused") but actively queried
- **Confidence**: HIGH

### UserSemanticProfilesTable
- **File**: `src/app/bartering/features/profile/db/UserSemanticProfilesTable.kt`
- **Purpose**: Semantic search embeddings and hashes for profile matching
- **Usage Level**: HIGH
- **Key Operations**: Insert (embedding), Update (new profile), Select (similarity queries)
- **Evidence**: UserProfileDaoImpl (34+ usages)
- **Confidence**: VERY HIGH

---

## POSTINGS & LISTINGS

### UserPostingsTable
- **File**: `src/app/bartering/features/postings/db/UserPostingsTable.kt`
- **Purpose**: User listings/postings for items to trade
- **Usage Level**: HIGH
- **Key Operations**: Insert (new listing), Update (details), Delete (expiration), Select (search/browse)
- **Evidence**: UserPostingDaoImpl (primary)
- **Confidence**: VERY HIGH

### PostingAttributesLinkTable
- **File**: `src/app/bartering/features/postings/db/UserPostingsTable.kt`
- **Purpose**: Many-to-many relationship between postings and attributes
- **Usage Level**: HIGH
- **Key Operations**: BatchInsert (attributes), Delete (update), Select (join queries)
- **Evidence**: UserPostingDaoImpl (36+ usages)
- **Confidence**: VERY HIGH

---

## CATEGORIZATION & TAXONOMY

### AttributeCategoriesLinkTable
- **File**: `src/app/bartering/features/categories/AttributeCategoriesLinkTable.kt`
- **Purpose**: Many-to-many mapping of attributes to categories
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (categorize), Select (category listing)
- **Evidence**: CategoriesDaoImpl, AttributesDaoImpl
- **Confidence**: HIGH

### AttributesMasterTable
- **File**: `src/app/bartering/features/attributes/db/AttributesMasterTable.kt`
- **Purpose**: Master list of all possible attributes in the system
- **Usage Level**: MEDIUM
- **Key Operations**: Select (attribute list), Insert (new attribute), rarely updated
- **Evidence**: CategoriesDaoImpl
- **Confidence**: HIGH

### CategoriesMasterTable
- **File**: `src/app/bartering/features/categories/CategoriesMasterTable.kt`
- **Purpose**: Master list of all categories in the system
- **Usage Level**: MEDIUM
- **Key Operations**: Select (category list), Insert (new category), rarely updated
- **Evidence**: CategoriesDaoImpl
- **Confidence**: HIGH

---

## DEVICE MANAGEMENT

### UserDeviceKeysTable
- **File**: `src/app/bartering/features/authentication/db/UserDeviceKeysTable.kt`
- **Purpose**: Multi-device support with public key cryptography
- **Usage Level**: HIGH
- **Key Operations**: Insert (register device), Update (activity/status), Select (device list), Delete (deactivate)
- **Evidence**: AuthenticationDaoImpl (60+ usages)
- **Confidence**: VERY HIGH

---

## DEVICE MIGRATION

### MigrationSessionsTable
- **File**: `src/app/bartering/features/migration/db/MigrationTables.kt`
- **Purpose**: Track active device migration sessions
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (session), Update (progress), Select (status), Delete (cleanup)
- **Evidence**: MigrationDao, user deletion
- **Confidence**: HIGH

### MigrationAuditLogTable
- **File**: `src/app/bartering/features/migration/db/MigrationTables.kt`
- **Purpose**: Audit trail of device migrations
- **Usage Level**: LOW (audit only)
- **Key Operations**: Insert (log entry), Select (historical queries)
- **Evidence**: MigrationDao
- **Confidence**: HIGH

---

## FEDERATION

### FederatedUsersTable
- **File**: `src/app/bartering/features/federation/db/FederatedUsersTable.kt`
- **Purpose**: User profiles replicated from federated servers
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (sync), Update (profile), Select (lookup), Delete (unsync)
- **Evidence**: FederationDaoImpl, FederatedUserDaoImpl
- **Confidence**: HIGH

### FederatedServersTable
- **File**: `src/app/bartering/features/federation/db/FederatedServersTable.kt`
- **Purpose**: Registry of trusted federated servers
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (add server), Update (status), Select (server list), Delete (remove)
- **Evidence**: FederationDaoImpl
- **Confidence**: HIGH

### FederatedPostingsTable
- **File**: `src/app/bartering/features/federation/db/FederatedPostingsTable.kt`
- **Purpose**: Replicated postings from federated servers
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (sync), Update (refresh), Select (search), Delete (unsync)
- **Evidence**: FederationDaoImpl
- **Confidence**: HIGH

### LocalServerIdentityTable
- **File**: `src/app/bartering/features/federation/db/LocalServerIdentityTable.kt`
- **Purpose**: Local server's federation credentials and identity
- **Usage Level**: MEDIUM
- **Key Operations**: Insert (setup - once), Select (identity), Update (rotate keys)
- **Status**: Singleton-like table (1 active row expected)
- **Evidence**: FederationDaoImpl
- **Confidence**: HIGH

### FederationAuditLogTable
- **File**: `src/app/bartering/features/federation/db/FederationAuditLogTable.kt`
- **Purpose**: Audit trail of federation sync operations
- **Usage Level**: LOW (audit only)
- **Key Operations**: Insert (log entry), Select (investigation)
- **Evidence**: FederationDaoImpl
- **Confidence**: HIGH

---

## SUMMARY TABLE

| Table | Feature | Usage | Status |
|-------|---------|-------|--------|
| UserRegistrationDataTable | Profile | CRITICAL | ✅ Active |
| UserProfilesTable | Profile | HIGH | ✅ Active |
| UserAttributesTable | Attributes | HIGH | ✅ Active |
| UserPrivacyConsentsTable | Compliance | HIGH | ✅ Active |
| UserSemanticProfilesTable | Search | HIGH | ✅ Active |
| ReviewsTable | Reviews | HIGH | ✅ Active |
| BarterTransactionsTable | Reviews | HIGH | ✅ Active |
| ReputationsTable | Reviews | HIGH | ✅ Active |
| WalletsTable | Wallet | HIGH | ✅ Active |
| UserRelationshipsTable | Relationships | HIGH | ✅ Active |
| UserPostingsTable | Postings | HIGH | ✅ Active |
| UserDeviceKeysTable | Auth | HIGH | ✅ Active |
| UserPremiumEntitlementsTable | Purchases | HIGH | ✅ Active |
| ComplianceAuditLogTable | Compliance | HIGH | ✅ Active |
| ComplianceErasureTasksTable | Compliance | HIGH | ✅ Active |
| DataSubjectRequestsTable | Compliance | HIGH | ✅ Active |
| UserPresenceTable | Activity | MEDIUM | ✅ Active |
| PostingAttributesLinkTable | Postings | MEDIUM | ✅ Active |
| MatchHistoryTable | Notifications | MEDIUM | ✅ Active |
| ReadReceiptsTable | Chat | MEDIUM | ✅ Active |
| OfflineMessagesTable | Chat | MEDIUM | ✅ Active |
| EncryptedFilesTable | Chat | MEDIUM | ✅ Active |
| FederatedUsersTable | Federation | MEDIUM | ✅ Active |
| FederatedPostingsTable | Federation | MEDIUM | ✅ Active |
| DeviceTrackingTable | Fraud | LOW | ⚠️ Incomplete |
| IpTrackingTable | Fraud | LOW | ⚠️ Incomplete |
| UserLocationChangesTable | Fraud | LOW | ⚠️ Incomplete |
| RiskPatternsTable | Reviews | MEDIUM | ✅ Active |
| UserDailyActivityStatsTable | Analytics | MEDIUM | ✅ Active |
| (All compliance, federation, purchase, etc.) | Various | MEDIUM | ✅ Active |

---

## Reference Legend

- **CRITICAL**: Without this table, core functionality breaks
- **HIGH**: Regular active usage, essential to feature
- **MEDIUM**: Active usage, supporting feature
- **LOW**: Minimal usage, audit/configuration only
- **✅ Active**: Table is actively used
- **⚠️ Incomplete**: Table exists but usage appears incomplete
