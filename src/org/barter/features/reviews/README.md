# Reviews & Reputation System

This package implements a comprehensive, abuse-resistant reputation and review system for the barter app. 
The system is designed to prevent common attack vectors while maintaining fairness and transparency.

## Overview

After completing a barter transaction, users can review each other with ratings, text reviews, 
and transaction status markers. The system implements multiple layers of abuse prevention to ensure 
reputation scores accurately reflect user trustworthiness.

## Core Features

### 1. **Transaction-Locked Reviews**
- Reviews can only be submitted after a confirmed barter transaction
- Both parties must mark the transaction as completed
- One review per transaction per user (prevents spam)

### 2. **Blind Review Period**
- Reviews are encrypted and hidden until both parties submit
- Prevents reciprocal manipulation ("I'll give you 5 stars if you give me 5 stars")
- 14-day deadline - reviews auto-reveal if one party doesn't submit

### 3. **Weighted Reputation System**
- Not all reviews carry equal weight
- Factors affecting weight:
  - Reviewer's account type (verified business > individual)
  - Transaction value (higher value = more weight)
  - Reviewer's own reputation (trusted reviewers carry more weight)
  - Account age (new accounts have reduced weight)

### 4. **Risk Analysis & Fraud Detection**
- Multi-factor Sybil attack detection via `RiskAnalysisService`:
  - Same device/IP address detection
  - GPS location proximity analysis
  - Account age correlation
  - Trade diversity scoring (prevents wash trading)
  - Contact information matching
- Comprehensive `RiskAnalysisReport` provides:
  - Overall risk score (0.0-1.0) with 5-tier classification
  - Individual component scores (device, IP, location, behavior)
  - Detected suspicious patterns with severity levels
  - Actionable recommendations based on risk level
  - Automatic manual review flags for high-risk transactions
- Automatic flagging and blocking of suspicious transactions

### 5. **Review Appeals & Moderation**
- Users can contest unfair reviews
- Evidence-based appeal system
- Human moderation queue for disputed reviews
- Public response mechanism (reply to reviews)

### 6. **Trust Levels & Badges**
- Progressive trust system based on trading history
- Earned badges for achievements (Top Rated, Veteran Trader, etc.)
- Higher trust levels unlock more features and carry more weight

## Transaction Statuses

Users can mark transactions with the following statuses:

| Status | Description | Impact on Reputation |
|--------|-------------|---------------------|
| `done` | Successful completion | Positive (if good rating) |
| `cancelled` | Mutually cancelled | Neutral (no review) |
| `expired` | No response/timeout | Neutral (no review) |
| `no_deal` | Decided not to proceed | Neutral (no review) |
| `scam` | Fraudulent behavior | **Negative** + triggers moderation |
| `disputed` | Requires mediation | **Flagged** for review |

## Abuse Prevention Mechanisms

### 1. **Review Bombing Prevention**
- ✅ Verified transaction requirement
- ✅ One review per transaction limit
- ✅ 30-90 day review window
- ✅ Account age threshold (14+ days)
- ✅ Review velocity limits (max 5/day)
- ✅ Device/IP fingerprinting

### 2. **Reciprocal Review Prevention**
- ✅ Blind review submission
- ✅ Reviews hidden until both submit
- ✅ Pattern detection for mutual 5-star reviews
- ✅ Transaction value weighting

### 3. **Sybil Attack Prevention**
- ✅ Device fingerprinting
- ✅ Geolocation anomaly detection
- ✅ Social graph analysis
- ✅ Payment/contact correlation
- ✅ Progressive trust system
- ✅ Trade diversity scoring

### 4. **Extortion Prevention**
- ✅ Escrow/mediation system
- ✅ Review appeal mechanism
- ✅ Public response capability
- ✅ Pattern detection for serial negative reviewers
- ✅ Two-stage ratings (communication + completion)

### 5. **Competitor Sabotage Prevention**
- ✅ Business account verification
- ✅ Review source transparency
- ✅ Minimum transaction value weighting
- ✅ Verified purchase badges
- ✅ Industry-specific rules

### 6. **Wash Trading Prevention**
- ✅ Network analysis (cluster detection)
- ✅ Trade diversity scoring
- ✅ Random transaction verification
- ✅ Time-decay reputation
- ✅ Cross-feature validation

## Database Schema

### Core Tables

#### `user_reputations`
Stores aggregated reputation scores for each user.

```sql
CREATE TABLE user_reputations (
    user_id VARCHAR(255) PRIMARY KEY,
    average_rating DECIMAL(3, 2),
    total_reviews INT DEFAULT 0,
    verified_reviews INT DEFAULT 0,
    trade_diversity_score DECIMAL(3, 2) DEFAULT 0.5,
    trust_level VARCHAR(50) DEFAULT 'new',
    last_updated TIMESTAMP DEFAULT NOW()
);
```

#### `barter_transactions`
Tracks all barter transactions between users.

```sql
CREATE TABLE barter_transactions (
    id VARCHAR(36) PRIMARY KEY,
    user1_id VARCHAR(255),
    user2_id VARCHAR(255),
    initiated_at TIMESTAMP DEFAULT NOW(),
    completed_at TIMESTAMP,
    status VARCHAR(50) DEFAULT 'pending',
    estimated_value DECIMAL(10, 2),
    location_confirmed BOOLEAN DEFAULT FALSE,
    risk_score DECIMAL(3, 2),
    INDEX idx_user1 (user1_id),
    INDEX idx_user2 (user2_id)
);
```

#### `reviews`
Stores all submitted reviews.

```sql
CREATE TABLE reviews (
    id VARCHAR(36) PRIMARY KEY,
    transaction_id VARCHAR(36) REFERENCES barter_transactions(id),
    reviewer_id VARCHAR(255),
    target_user_id VARCHAR(255),
    rating INT CHECK (rating BETWEEN 1 AND 5),
    review_text TEXT,
    transaction_status VARCHAR(50),
    review_weight DECIMAL(3, 2) DEFAULT 1.0,
    is_visible BOOLEAN DEFAULT FALSE,
    submitted_at TIMESTAMP DEFAULT NOW(),
    revealed_at TIMESTAMP,
    is_verified BOOLEAN DEFAULT FALSE,
    moderation_status VARCHAR(50),
    INDEX idx_transaction (transaction_id),
    INDEX idx_reviewer (reviewer_id),
    INDEX idx_target (target_user_id),
    INDEX idx_visible (target_user_id, is_visible)
);
```

#### `pending_reviews`
Encrypted reviews awaiting blind reveal.

```sql
CREATE TABLE pending_reviews (
    transaction_id VARCHAR(255),
    reviewer_id VARCHAR(255),
    encrypted_review TEXT,
    submitted_at TIMESTAMP DEFAULT NOW(),
    reveal_deadline TIMESTAMP,
    revealed BOOLEAN DEFAULT FALSE,
    revealed_at TIMESTAMP,
    PRIMARY KEY (transaction_id, reviewer_id),
    INDEX idx_deadline (reveal_deadline, revealed)
);
```

### Supporting Tables

- `review_responses` - User responses to received reviews
- `review_appeals` - Disputed reviews awaiting moderation
- `review_audit_log` - Complete audit trail for abuse detection
- `reputation_badges` - Earned achievement badges
- `review_moderation_queue` - Reviews flagged for human review

## API Flow Example

### Submitting a Review

```kotlin
// 1. Check eligibility
val eligibility = reviewEligibilityService.checkReviewEligibility(
    reviewerId = currentUserId,
    targetUserId = otherUserId,
    transactionId = transactionId,
    // ... other params
)

if (!eligibility.canReview) {
    return BadRequest(eligibility.reason)
}

// 2. Perform comprehensive risk analysis
val riskReport = riskAnalysisService.analyzeTransactionRisk(
    transactionId = transactionId,
    user1Id = currentUserId,
    user2Id = otherUserId,
    getAccountAge = { userId -> profileDao.getUserCreatedAt(userId) },
    getTradingPartners = { userId -> transactionDao.getTradingPartners(userId) }
)

// 3. Calculate review weight
val weight = reviewWeightService.calculateReviewWeight(
    review = submission,
    reviewerAccountType = accountType,
    transactionValue = transactionValue,
    reviewerReputation = reputation,
    isVerifiedTransaction = false
)

// 4. Encrypt and store (blind review)
val secretKey = blindReviewService.generateSecretKey()
val encrypted = blindReviewService.encryptReview(reviewJson, secretKey)
// Store in pending_reviews table

// 5. Check if both submitted, reveal if ready
if (bothPartiesSubmitted) {
    revealReviews(transactionId)
    updateReputationScores()
}
```

### Revealing Reviews

```kotlin
// Automatic background job checks for reviews ready to reveal
fun revealPendingReviews() {
    val readyToReveal = pendingReviewsDao.findReadyToReveal()
    
    for (pending in readyToReveal) {
        // Decrypt reviews
        val review1 = blindReviewService.decryptReview(pending.review1)
        val review2 = blindReviewService.decryptReview(pending.review2)
        
        // Insert into reviews table (now visible)
        reviewsDao.insertReview(review1)
        reviewsDao.insertReview(review2)
        
        // Update reputation scores
        reputationCalculationService.recalculateReputation(user1Id)
        reputationCalculationService.recalculateReputation(user2Id)
        
        // Notify users
        notificationService.sendReviewRevealedNotification(user1Id, user2Id)
    }
}
```

## Trust Levels

| Level | Requirements | Benefits |
|-------|--------------|----------|
| **New** | < 5 reviews | Limited review impact |
| **Emerging** | 5-19 reviews | Standard features |
| **Established** | 20-99 reviews | Increased review weight |
| **Trusted** | 100+ reviews, high diversity | Higher weight, dispute priority |
| **Verified** | Trusted + ID verified | Maximum trust, business features |

## Reputation Badges

Users can earn badges for achievements:

- 🔰 **Identity Verified** - Completed identity verification
- ⭐ **Top Rated** - 4.8+ rating with 50+ reviews
- 🏆 **Veteran Trader** - 100+ successful trades
- ⚡ **Quick Responder** - < 24hr average response time
- 🌐 **Community Connector** - High trade diversity score
- 🏢 **Verified Business** - Business registration verified
- ✅ **Dispute-Free** - No disputed transactions
- 🚀 **Fast Trader** - Completes trades faster than average

## Configuration

### Review Settings

```kotlin
const val MIN_ACCOUNT_AGE_DAYS = 14
const val MAX_REVIEWS_PER_DAY = 5
const val REVIEW_WINDOW_DAYS = 90
const val BLIND_REVIEW_DEADLINE_DAYS = 14
```

### Risk Thresholds

Risk levels are determined by the overall risk score (0.0-1.0):

| Risk Level | Score Range | Action |
|------------|-------------|--------|
| **MINIMAL** | 0.0 - 0.2 | Standard processing |
| **LOW** | 0.2 - 0.4 | Standard processing |
| **MEDIUM** | 0.4 - 0.6 | Reduced review weight (25%) |
| **HIGH** | 0.6 - 0.8 | Reduced review weight (50%), manual review required |
| **CRITICAL** | 0.8 - 1.0 | Transaction blocked, accounts flagged |

### Weight Limits

```kotlin
const val MIN_REVIEW_WEIGHT = 0.1
const val MAX_REVIEW_WEIGHT = 2.0
```

## Moderation Queue Priority

| Priority | Trigger | SLA |
|----------|---------|-----|
| **Urgent** | Safety concern, threats | < 1 hour |
| **High** | Multiple scam reports | < 24 hours |
| **Medium** | Single scam report | < 72 hours |
| **Low** | Appeal without evidence | < 7 days |

## Integration Points

### With Chat System
```kotlin
// Automatically create transaction when users start negotiating
chatService.onNegotiationStart { user1, user2 ->
    transactionDao.createTransaction(user1, user2, status = "pending")
}
```

### With Relationships System
```kotlin
// Update relationships after successful trade
transactionService.onTransactionComplete { transaction ->
    relationshipsDao.createRelationship(
        user1, user2, RelationshipType.TRADED
    )
}
```

### With Notifications
```kotlin
// Notify users when reviews are revealed
reviewRevealService.onReveal { user1, user2, transaction ->
    notificationService.sendReviewRevealedNotification(user1)
    notificationService.sendReviewRevealedNotification(user2)
}
```

## Future Enhancements

- [ ] Machine learning for review text sentiment analysis
- [ ] Automated fake review detection using ML
- [ ] Blockchain-based immutable review records
- [ ] Cross-platform reputation portability
- [ ] Reputation insurance/escrow for high-value trades
- [ ] Community-based moderation (jury system)
- [ ] Reputation recovery program for reformed users
- [ ] API for third-party reputation verification

## Testing Recommendations

### Unit Tests
- Review eligibility edge cases
- Risk score calculations
- Weight calculation logic
- Blind review encryption/decryption
- Trust level transitions

### Integration Tests
- End-to-end review submission flow
- Blind review reveal process
- Appeal and moderation workflow
- Reputation recalculation accuracy

### Security Tests
- Encryption strength
- SQL injection prevention
- Rate limiting effectiveness
- Authentication bypass attempts

## Monitoring & Alerts

### Key Metrics
- Average review submission rate
- Percentage of disputed reviews
- Moderation queue size and age
- Risk score distribution
- Trust level distribution

### Alerts
- 🚨 Sudden spike in negative reviews (potential attack)
- 🚨 Moderation queue > 100 items
- 🚨 High-risk transaction rate > 10%
- 🚨 Review submission failures > 5%

## Security Considerations

1. **Encryption** - Blind reviews use AES-256 encryption
2. **Rate Limiting** - Max 5 reviews per day per user
3. **Audit Trail** - Complete logging of all review actions
4. **Input Validation** - All user inputs sanitized and validated
5. **Authentication** - All endpoints require valid authentication
6. **Authorization** - Users can only review their own transactions

## License & Compliance

- GDPR compliant (right to be forgotten, data export)
- Reviews can be deleted after account closure
- Personal data encrypted at rest
- Audit logs retained for compliance
