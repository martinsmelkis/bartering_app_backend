# Reviews System API Reference

Complete API documentation with serializable data classes for Flutter client integration.

## Base URL
```
http://your-server:8081/api/v1
```

## Authentication
All endpoints require signature-based authentication:
```
Headers:
  X-User-ID: {userId}
  X-Timestamp: {milliseconds since epoch}
  X-Signature: {ECDSA signature}
```

---

## 📦 Data Models

All request and response models are in `org.barter.features.reviews.model.ApiModels.kt`

### Common Models

```dart
// Transaction Response
class TransactionResponse {
  final String id;
  final String user1Id;
  final String user2Id;
  final int initiatedAt;
  final int? completedAt;
  final String status; // "pending", "done", "cancelled", "disputed", etc.
  final double? estimatedValue;
  final bool locationConfirmed;
  final double? riskScore;
}

// Review Response
class ReviewResponse {
  final String id;
  final String transactionId;
  final String reviewerId;
  final String targetUserId;
  final int rating; // 1-5
  final String? reviewText;
  final String transactionStatus;
  final double reviewWeight;
  final bool isVisible;
  final int submittedAt;
  final int? revealedAt;
  final bool isVerified;
  final String? moderationStatus;
}

// Reputation Response
class ReputationResponse {
  final String userId;
  final double averageRating; // 0.0-5.0
  final int totalReviews;
  final int verifiedReviews;
  final double tradeDiversityScore; // 0.0-1.0
  final String trustLevel; // "new", "emerging", "established", "trusted", "verified"
  final List<String> badges;
  final int lastUpdated;
}

// Error Response
class ErrorResponse {
  final String error;
  final String? code;
  final Map<String, String>? details;
}
```

---

## 🔄 Transaction Endpoints

### 1. Create Transaction

**POST** `/transactions/create`

Create a new barter transaction between two users.

**Request Body:**
```json
{
  "user1Id": "user123",
  "user2Id": "user456",
  "estimatedValue": 100.00
}
```

**Response (201 Created):**
```json
{
  "success": true,
  "transactionId": "trans-uuid-123"
}
```

**Flutter Example:**
```dart
Future<String> createTransaction(String user1Id, String user2Id, double? value) async {
  final response = await http.post(
    Uri.parse('$baseUrl/transactions/create'),
    headers: await getAuthHeaders(),
    body: jsonEncode({
      'user1Id': user1Id,
      'user2Id': user2Id,
      'estimatedValue': value,
    }),
  );
  
  final data = CreateTransactionResponse.fromJson(jsonDecode(response.body));
  return data.transactionId;
}
```

---

### 2. Update Transaction Status

**PUT** `/transactions/{id}/status`

Update the status of a transaction (e.g., mark as "done" when trade completes).

**Request Body:**
```json
{
  "status": "done"
}
```

**Valid Statuses:**
- `pending` - Initial state
- `done` - Successfully completed ✅
- `cancelled` - Mutually cancelled
- `expired` - Timed out
- `no_deal` - Talked but didn't trade
- `scam` - Report fraudulent behavior 🚩
- `disputed` - Requires mediation

**Response (200 OK):**
```json
{
  "success": true
}
```

**Flutter Example:**
```dart
Future<void> markTransactionDone(String transactionId) async {
  await http.put(
    Uri.parse('$baseUrl/transactions/$transactionId/status'),
    headers: await getAuthHeaders(),
    body: jsonEncode({'status': 'done'}),
  );
}
```

---

### 3. Get User's Transactions

**GET** `/transactions/user/{userId}`

Get all transactions for a user.

**Response (200 OK):**
```json
[
  {
    "id": "trans-123",
    "user1Id": "user123",
    "user2Id": "user456",
    "initiatedAt": 1704300000000,
    "completedAt": 1704400000000,
    "status": "done",
    "estimatedValue": 100.00,
    "locationConfirmed": true,
    "riskScore": 0.1
  }
]
```

**Flutter Example:**
```dart
Future<List<TransactionResponse>> getUserTransactions(String userId) async {
  final response = await http.get(
    Uri.parse('$baseUrl/transactions/user/$userId'),
    headers: await getAuthHeaders(),
  );
  
  final List<dynamic> data = jsonDecode(response.body);
  return data.map((json) => TransactionResponse.fromJson(json)).toList();
}
```

---

## ⭐ Review Endpoints

### 4. Check Review Eligibility

**GET** `/reviews/eligibility/{userId}/with/{otherUserId}`

Check if a user can review another user (for conversation deletion flow).

**Response (200 OK):**
```json
{
  "eligible": true,
  "transactionId": "trans-uuid-123",
  "reason": null,
  "otherUserName": "John Doe",
  "otherUserAvatarUrl": "https://...",
  "transactionCompletedAt": 1704400000000
}
```

**Response (Not Eligible):**
```json
{
  "eligible": false,
  "transactionId": null,
  "reason": "No completed transaction found",
  "otherUserName": "John Doe",
  "otherUserAvatarUrl": null,
  "transactionCompletedAt": null
}
```

**Flutter Example:**
```dart
Future<ReviewEligibilityResponse> checkCanReview(
  String userId, 
  String otherUserId
) async {
  final response = await http.get(
    Uri.parse('$baseUrl/reviews/eligibility/$userId/with/$otherUserId'),
    headers: await getAuthHeaders(),
  );
  
  return ReviewEligibilityResponse.fromJson(jsonDecode(response.body));
}

// Usage in swipe-to-delete flow:
void onConversationSwipe(String otherUserId) async {
  final eligibility = await checkCanReview(currentUserId, otherUserId);
  
  if (eligibility.eligible) {
    // Show review dialog
    showReviewDialog(
      transactionId: eligibility.transactionId!,
      otherUserId: otherUserId,
      otherUserName: eligibility.otherUserName,
    );
  } else {
    // Just archive conversation
    archiveConversation(otherUserId);
  }
}
```

---

### 5. Submit Review

**POST** `/reviews/submit`

Submit a review for a completed transaction.

**Request Body:**
```json
{
  "transactionId": "trans-uuid-123",
  "reviewerId": "user123",
  "targetUserId": "user456",
  "rating": 5,
  "reviewText": "Great trader! Very responsive.",
  "transactionStatus": "done"
}
```

**Validation Rules:**
- `rating`: Must be 1-5
- `reviewText`: Optional, max 500 characters
- `transactionStatus`: Must match transaction outcome

**Response (201 Created):**
```json
{
  "success": true,
  "reviewId": "review-uuid-789",
  "message": "Review submitted. It will be visible after both parties submit reviews or 14-day deadline."
}
```

**Flutter Example:**
```dart
Future<SubmitReviewResponse> submitReview({
  required String transactionId,
  required String reviewerId,
  required String targetUserId,
  required int rating,
  String? reviewText,
  required String transactionStatus,
}) async {
  final response = await http.post(
    Uri.parse('$baseUrl/reviews/submit'),
    headers: await getAuthHeaders(),
    body: jsonEncode({
      'transactionId': transactionId,
      'reviewerId': reviewerId,
      'targetUserId': targetUserId,
      'rating': rating,
      'reviewText': reviewText,
      'transactionStatus': transactionStatus,
    }),
  );
  
  return SubmitReviewResponse.fromJson(jsonDecode(response.body));
}
```

---

### 6. Get User Reviews

**GET** `/reviews/user/{userId}`

Get all visible reviews for a user.

**Response (200 OK):**
```json
{
  "userId": "user456",
  "reviews": [
    {
      "id": "review-123",
      "transactionId": "trans-123",
      "reviewerId": "user789",
      "targetUserId": "user456",
      "rating": 5,
      "reviewText": "Excellent trader!",
      "transactionStatus": "done",
      "reviewWeight": 1.2,
      "isVisible": true,
      "submittedAt": 1704400000000,
      "revealedAt": 1704500000000,
      "isVerified": true,
      "moderationStatus": null
    }
  ],
  "totalCount": 23,
  "averageRating": 4.8
}
```

**Flutter Example:**
```dart
Future<UserReviewsResponse> getUserReviews(String userId) async {
  final response = await http.get(
    Uri.parse('$baseUrl/reviews/user/$userId'),
    headers: await getAuthHeaders(),
  );
  
  return UserReviewsResponse.fromJson(jsonDecode(response.body));
}
```

---

### 7. Get Transaction Reviews

**GET** `/reviews/transaction/{transactionId}`

Get reviews for a specific transaction (both parties must be involved).

**Response (200 OK):**
```json
[
  {
    "id": "review-123",
    "transactionId": "trans-123",
    "reviewerId": "user123",
    "targetUserId": "user456",
    "rating": 5,
    "reviewText": "Great experience!",
    "transactionStatus": "done",
    "reviewWeight": 1.0,
    "isVisible": true,
    "submittedAt": 1704400000000,
    "revealedAt": 1704500000000,
    "isVerified": false,
    "moderationStatus": null
  },
  {
    "id": "review-124",
    "transactionId": "trans-123",
    "reviewerId": "user456",
    "targetUserId": "user123",
    "rating": 5,
    "reviewText": "Smooth trade!",
    "transactionStatus": "done",
    "reviewWeight": 1.0,
    "isVisible": true,
    "submittedAt": 1704401000000,
    "revealedAt": 1704500000000,
    "isVerified": false,
    "moderationStatus": null
  }
]
```

---

## 🏆 Reputation Endpoints

### 8. Get Reputation

**GET** `/reputation/{userId}`

Get comprehensive reputation score for a user.

**Response (200 OK):**
```json
{
  "userId": "user456",
  "averageRating": 4.8,
  "totalReviews": 23,
  "verifiedReviews": 15,
  "tradeDiversityScore": 0.85,
  "trustLevel": "established",
  "badges": [
    "top_rated",
    "veteran_trader",
    "quick_responder"
  ],
  "lastUpdated": 1704500000000
}
```

**Trust Levels:**
- `new` - < 5 reviews
- `emerging` - 5-19 reviews
- `established` - 20-99 reviews
- `trusted` - 100+ reviews, high diversity
- `verified` - Trusted + identity verified

**Flutter Example:**
```dart
Future<ReputationResponse> getReputation(String userId) async {
  final response = await http.get(
    Uri.parse('$baseUrl/reputation/$userId'),
    headers: await getAuthHeaders(),
  );
  
  return ReputationResponse.fromJson(jsonDecode(response.body));
}

// Display in UI:
Widget buildReputationDisplay(ReputationResponse rep) {
  return Column(
    children: [
      Text('${rep.averageRating.toStringAsFixed(1)} ⭐'),
      Text('${rep.totalReviews} reviews'),
      Text('Trust Level: ${rep.trustLevel}'),
      Wrap(
        children: rep.badges.map((badge) => 
          Chip(label: Text(badge))
        ).toList(),
      ),
    ],
  );
}
```

---

### 9. Get User Badges

**GET** `/reputation/{userId}/badges`

Get detailed badge information for a user.

**Response (200 OK):**
```json
{
  "userId": "user456",
  "badges": [
    {
      "type": "top_rated",
      "name": "Top Rated",
      "description": "Top Rated Seller",
      "earnedAt": 1704400000000
    },
    {
      "type": "veteran_trader",
      "name": "Veteran Trader",
      "description": "Veteran Trader - 100+ trades",
      "earnedAt": 1704300000000
    }
  ]
}
```

**Available Badges:**
- `identity_verified` - Identity Verified
- `veteran_trader` - 100+ successful trades
- `top_rated` - 4.8+ rating with 50+ reviews
- `quick_responder` - Always responds within 24 hours
- `community_connector` - High trade diversity score
- `verified_business` - Verified business registration
- `dispute_free` - No disputed transactions
- `fast_trader` - Completes trades faster than average

---

## 🚨 Error Handling

All endpoints return consistent error responses:

**400 Bad Request:**
```json
{
  "error": "Invalid request: rating must be between 1 and 5"
}
```

**403 Forbidden:**
```json
{
  "error": "You can only review your own transactions"
}
```

**404 Not Found:**
```json
{
  "error": "Transaction not found"
}
```

**500 Internal Server Error:**
```json
{
  "error": "Failed to retrieve reputation: database connection error"
}
```

**Flutter Error Handling:**
```dart
try {
  final reputation = await getReputation(userId);
  // Handle success
} on HttpException catch (e) {
  if (e.statusCode == 404) {
    // User not found
  } else if (e.statusCode == 403) {
    // Permission denied
  }
} catch (e) {
  // Network error or other issue
  showError('Failed to load reputation');
}
```

---

## 📱 Complete Flutter Integration Example

```dart
class ReviewsApiClient {
  final String baseUrl;
  final AuthService authService;
  
  ReviewsApiClient(this.baseUrl, this.authService);
  
  Future<Map<String, String>> _getHeaders() async {
    final auth = await authService.getAuthHeaders();
    return {
      ...auth,
      'Content-Type': 'application/json',
    };
  }
  
  // Create transaction
  Future<String> createTransaction(
    String user1Id, 
    String user2Id, 
    {double? estimatedValue}
  ) async {
    final response = await http.post(
      Uri.parse('$baseUrl/transactions/create'),
      headers: await _getHeaders(),
      body: jsonEncode({
        'user1Id': user1Id,
        'user2Id': user2Id,
        if (estimatedValue != null) 'estimatedValue': estimatedValue,
      }),
    );
    
    if (response.statusCode == 201) {
      final data = jsonDecode(response.body);
      return data['transactionId'];
    }
    throw HttpException('Failed to create transaction', 
      uri: Uri.parse('$baseUrl/transactions/create'));
  }
  
  // Mark transaction done
  Future<void> markTransactionDone(String transactionId) async {
    final response = await http.put(
      Uri.parse('$baseUrl/transactions/$transactionId/status'),
      headers: await _getHeaders(),
      body: jsonEncode({'status': 'done'}),
    );
    
    if (response.statusCode != 200) {
      throw HttpException('Failed to update transaction');
    }
  }
  
  // Check review eligibility
  Future<ReviewEligibilityResponse> checkReviewEligibility(
    String userId,
    String otherUserId,
  ) async {
    final response = await http.get(
      Uri.parse('$baseUrl/reviews/eligibility/$userId/with/$otherUserId'),
      headers: await _getHeaders(),
    );
    
    return ReviewEligibilityResponse.fromJson(jsonDecode(response.body));
  }
  
  // Submit review
  Future<String> submitReview({
    required String transactionId,
    required String reviewerId,
    required String targetUserId,
    required int rating,
    String? reviewText,
    required String transactionStatus,
  }) async {
    final response = await http.post(
      Uri.parse('$baseUrl/reviews/submit'),
      headers: await _getHeaders(),
      body: jsonEncode({
        'transactionId': transactionId,
        'reviewerId': reviewerId,
        'targetUserId': targetUserId,
        'rating': rating,
        if (reviewText != null) 'reviewText': reviewText,
        'transactionStatus': transactionStatus,
      }),
    );
    
    if (response.statusCode == 201) {
      final data = jsonDecode(response.body);
      return data['reviewId'];
    }
    throw HttpException('Failed to submit review');
  }
  
  // Get reputation
  Future<ReputationResponse> getReputation(String userId) async {
    final response = await http.get(
      Uri.parse('$baseUrl/reputation/$userId'),
      headers: await _getHeaders(),
    );
    
    return ReputationResponse.fromJson(jsonDecode(response.body));
  }
}
```

---

## 🔒 Security Notes

1. **All endpoints require authentication** via signature verification
2. **Users can only**:
   - Review transactions they're part of
   - View their own transaction history
   - Update transactions they're involved in
3. **Rate limiting**: Max 5 reviews per day per user
4. **Review validation**: All inputs sanitized server-side
5. **Audit trail**: All actions logged for abuse detection

---

## ⚡ Performance Tips

1. **Cache reputation scores** client-side (server caches for 1 hour)
2. **Batch transaction queries** when possible
3. **Lazy load reviews** (paginate if needed)
4. **Pre-fetch eligibility** when user hovers over delete button
5. **Optimistic UI updates** for better UX

---

## 📚 See Also

- `CLIENT_WORKFLOW.md` - Complete UI/UX flow
- `README.md` - System overview
- `ABUSE_PREVENTION_GUIDE.md` - Security details
