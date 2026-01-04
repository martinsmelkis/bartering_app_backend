# Flutter Client Review Workflow

## Overview

When a user swipes a conversation to delete, the app should check if they can review the other party and prompt them to do so before archiving/deleting the conversation.

## Complete Workflow

### 1. Conversation Swipe-to-Delete Action

```dart
// User swipes conversation with user456 to delete
onConversationSwipe(String otherUserId) async {
  // Step 1: Check if there's a completed transaction
  final canReview = await checkReviewEligibility(otherUserId);
  
  if (canReview.eligible) {
    // Show review prompt dialog
    showReviewPromptDialog(
      otherUserId: otherUserId,
      transactionId: canReview.transactionId,
    );
  } else {
    // Just archive/delete the conversation
    archiveConversation(otherUserId);
  }
}
```

### 2. API Call Sequence

#### Step 2.1: Check Review Eligibility

**New Endpoint Needed**: `GET /api/v1/reviews/eligibility/{userId}/with/{otherUserId}`

```http
GET /api/v1/reviews/eligibility/user123/with/user456
Headers:
  X-User-ID: user123
  X-Timestamp: 1704398400000
  X-Signature: {signature}

Response 200 OK:
{
  "eligible": true,
  "transactionId": "trans-uuid-123",
  "reason": null,
  "otherUserName": "John Doe",
  "transactionCompletedAt": 1704300000000
}

Response 200 OK (not eligible):
{
  "eligible": false,
  "transactionId": null,
  "reason": "No completed transaction found",
  "otherUserName": "John Doe"
}
```

#### Step 2.2: Submit Review (if user proceeds)

**Existing Endpoint**: `POST /api/v1/reviews/submit`

```http
POST /api/v1/reviews/submit
Headers:
  X-User-ID: user123
  X-Timestamp: 1704398400000
  X-Signature: {signature}
Body:
{
  "transactionId": "trans-uuid-123",
  "reviewerId": "user123",
  "targetUserId": "user456",
  "rating": 5,
  "reviewText": "Great trader! Very responsive and fair.",
  "transactionStatus": "done"
}

Response 201 Created:
{
  "success": true,
  "reviewId": "review-uuid-456",
  "message": "Review submitted. It will be visible after both parties submit reviews or 14-day deadline."
}
```

#### Step 2.3: Archive Conversation

After review submission (or skip), archive the conversation locally.

## Review Screen UI Components

### Screen Layout

```
┌─────────────────────────────────────┐
│  ← Back     Review John Doe     ✓   │ ← Header with back and submit
├─────────────────────────────────────┤
│                                     │
│   How was your trade with John?     │ ← Title
│                                     │
│   ┌─────────────────────────────┐   │
│   │                             │   │
│   │    [User Avatar/Photo]      │   │ ← Other user's profile
│   │      John Doe               │   │
│   │   🌟 4.8 · 23 trades       │   │ ← Their current reputation
│   │                             │   │
│   └─────────────────────────────┘   │
│                                     │
│   Rating *                          │ ← Required field marker
│   ⭐⭐⭐⭐⭐                          │ ← Star rating (tappable)
│                                     │
│   How did it go? *                  │ ← Transaction outcome
│   ┌─────────────────────────────┐   │
│   │ ✓ Successful Trade          │   │ ← Selected
│   │   Cancelled                 │   │
│   │   No Deal                   │   │
│   │   🚩 Report Scam            │   │ ← Red/warning color
│   └─────────────────────────────┘   │
│                                     │
│   Tell us more (optional)           │
│   ┌─────────────────────────────┐   │
│   │                             │   │ ← Text area
│   │ [Write your review...]      │   │
│   │                             │   │
│   │                             │   │
│   └─────────────────────────────┘   │
│   0/500 characters                  │
│                                     │
│   ──────────────────────────────    │
│                                     │
│   ℹ️ Review Guidelines              │ ← Expandable info section
│   • Be honest and fair              │
│   • Reviews become visible after    │
│     both parties submit             │
│   • False reports may result in     │
│     account suspension              │
│                                     │
│   [Submit Review]                   │ ← Primary action button
│   [Skip for Now]                    │ ← Secondary option
│                                     │
└─────────────────────────────────────┘
```

### Detailed Components

#### 1. **Header Bar**
```dart
AppBar(
  leading: IconButton(
    icon: Icon(Icons.arrow_back),
    onPressed: () => Navigator.pop(context),
  ),
  title: Text('Review ${otherUserName}'),
  actions: [
    // Optional: Quick submit if all required filled
    IconButton(
      icon: Icon(Icons.check),
      onPressed: isFormValid ? submitReview : null,
    ),
  ],
)
```

#### 2. **User Profile Card**
```dart
Card(
  child: Padding(
    padding: EdgeInsets.all(16),
    child: Column(
      children: [
        CircleAvatar(
          radius: 40,
          backgroundImage: NetworkImage(otherUser.avatarUrl),
        ),
        SizedBox(height: 8),
        Text(
          otherUser.name,
          style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
        ),
        SizedBox(height: 4),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.star, color: Colors.amber, size: 16),
            SizedBox(width: 4),
            Text('${otherUser.rating} · ${otherUser.tradeCount} trades'),
          ],
        ),
        // Optional: Show their badges
        Wrap(
          spacing: 8,
          children: otherUser.badges.map((badge) => 
            Chip(label: Text(badge))
          ).toList(),
        ),
      ],
    ),
  ),
)
```

#### 3. **Star Rating Widget**
```dart
Row(
  mainAxisAlignment: MainAxisAlignment.center,
  children: List.generate(5, (index) {
    return GestureDetector(
      onTap: () => setState(() => rating = index + 1),
      child: Icon(
        index < rating ? Icons.star : Icons.star_border,
        color: Colors.amber,
        size: 48,
      ),
    );
  }),
)

// Below stars, show descriptive text
Text(
  getRatingDescription(rating),
  style: TextStyle(color: Colors.grey[600]),
)

String getRatingDescription(int rating) {
  switch (rating) {
    case 5: return "Excellent";
    case 4: return "Good";
    case 3: return "Okay";
    case 2: return "Poor";
    case 1: return "Very Bad";
    default: return "Tap to rate";
  }
}
```

#### 4. **Transaction Status Selector**
```dart
Column(
  crossAxisAlignment: CrossAxisAlignment.start,
  children: [
    Text(
      'How did it go? *',
      style: TextStyle(fontSize: 16, fontWeight: FontWeight.w500),
    ),
    SizedBox(height: 8),
    ...TransactionStatus.values.map((status) {
      return RadioListTile<TransactionStatus>(
        value: status,
        groupValue: selectedStatus,
        onChanged: (value) => setState(() => selectedStatus = value),
        title: Row(
          children: [
            Icon(
              _getStatusIcon(status),
              color: _getStatusColor(status),
            ),
            SizedBox(width: 8),
            Text(_getStatusLabel(status)),
          ],
        ),
      );
    }).toList(),
  ],
)

IconData _getStatusIcon(TransactionStatus status) {
  switch (status) {
    case TransactionStatus.done:
      return Icons.check_circle;
    case TransactionStatus.cancelled:
      return Icons.cancel;
    case TransactionStatus.noDeal:
      return Icons.handshake_outlined;
    case TransactionStatus.scam:
      return Icons.report;
    default:
      return Icons.help_outline;
  }
}

Color _getStatusColor(TransactionStatus status) {
  switch (status) {
    case TransactionStatus.done:
      return Colors.green;
    case TransactionStatus.scam:
      return Colors.red;
    default:
      return Colors.grey;
  }
}

String _getStatusLabel(TransactionStatus status) {
  switch (status) {
    case TransactionStatus.done:
      return "Successful Trade";
    case TransactionStatus.cancelled:
      return "Cancelled";
    case TransactionStatus.noDeal:
      return "Talked but no deal";
    case TransactionStatus.scam:
      return "🚩 Report Scam";
    default:
      return status.toString();
  }
}
```

#### 5. **Review Text Area**
```dart
TextField(
  controller: reviewTextController,
  maxLength: 500,
  maxLines: 5,
  decoration: InputDecoration(
    hintText: 'Share your experience (optional)',
    border: OutlineInputBorder(),
    helperText: 'Be specific and constructive',
  ),
  onChanged: (text) {
    // Real-time character count
    setState(() {});
  },
)
```

#### 6. **Guidelines Section (Expandable)**
```dart
ExpansionTile(
  leading: Icon(Icons.info_outline),
  title: Text('Review Guidelines'),
  children: [
    Padding(
      padding: EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _guideline('Be honest and fair'),
          _guideline('Focus on your actual experience'),
          _guideline('Reviews become visible after both parties submit'),
          _guideline('You have 90 days to submit a review'),
          _guideline('False reports may result in account suspension'),
        ],
      ),
    ),
  ],
)

Widget _guideline(String text) {
  return Padding(
    padding: EdgeInsets.only(bottom: 8),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(Icons.check, size: 16, color: Colors.green),
        SizedBox(width: 8),
        Expanded(child: Text(text)),
      ],
    ),
  );
}
```

#### 7. **Action Buttons**
```dart
Column(
  children: [
    // Primary button - Submit
    ElevatedButton(
      onPressed: isFormValid ? submitReview : null,
      style: ElevatedButton.styleFrom(
        minimumSize: Size(double.infinity, 48),
        backgroundColor: Theme.of(context).primaryColor,
      ),
      child: Text('Submit Review'),
    ),
    SizedBox(height: 8),
    // Secondary button - Skip
    TextButton(
      onPressed: () {
        showSkipConfirmationDialog();
      },
      child: Text('Skip for Now'),
    ),
  ],
)

bool get isFormValid {
  return rating > 0 && selectedStatus != null;
}
```

### Special Cases

#### A. **Scam Report Flow**

When user selects "Report Scam":

```dart
if (selectedStatus == TransactionStatus.scam) {
  // Show additional warning dialog
  showDialog(
    context: context,
    builder: (context) => AlertDialog(
      title: Row(
        children: [
          Icon(Icons.warning, color: Colors.red),
          SizedBox(width: 8),
          Text('Report Scam'),
        ],
      ),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Are you sure you want to report this user for scam?'),
          SizedBox(height: 16),
          Text(
            'This will:',
            style: TextStyle(fontWeight: FontWeight.bold),
          ),
          Text('• Flag this transaction for moderator review'),
          Text('• Potentially suspend the other user'),
          Text('• Require evidence from you'),
          SizedBox(height: 16),
          Text(
            'False reports may result in penalties to your account.',
            style: TextStyle(color: Colors.red, fontSize: 12),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text('Cancel'),
        ),
        ElevatedButton(
          onPressed: () {
            Navigator.pop(context);
            proceedWithScamReport();
          },
          style: ElevatedButton.styleFrom(backgroundColor: Colors.red),
          child: Text('Report'),
        ),
      ],
    ),
  );
}
```

#### B. **Skip Confirmation Dialog**

```dart
showDialog(
  context: context,
  builder: (context) => AlertDialog(
    title: Text('Skip Review?'),
    content: Text(
      'You can review this user later from your transaction history. '
      'Reviews help build trust in the community.',
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: Text('Go Back'),
      ),
      TextButton(
        onPressed: () {
          Navigator.pop(context); // Close dialog
          Navigator.pop(context); // Close review screen
          archiveConversation();
        },
        child: Text('Skip'),
      ),
    ],
  ),
);
```

#### C. **Success Confirmation**

After successful submission:

```dart
showDialog(
  context: context,
  barrierDismissible: false,
  builder: (context) => AlertDialog(
    title: Row(
      children: [
        Icon(Icons.check_circle, color: Colors.green),
        SizedBox(width: 8),
        Text('Review Submitted!'),
      ],
    ),
    content: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Thank you for your feedback!'),
        SizedBox(height: 16),
        Container(
          padding: EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: Colors.blue[50],
            borderRadius: BorderRadius.circular(8),
          ),
          child: Row(
            children: [
              Icon(Icons.visibility_off, size: 20, color: Colors.blue),
              SizedBox(width: 8),
              Expanded(
                child: Text(
                  'Your review will be visible after ${otherUserName} '
                  'submits their review, or in 14 days.',
                  style: TextStyle(fontSize: 12),
                ),
              ),
            ],
          ),
        ),
      ],
    ),
    actions: [
      ElevatedButton(
        onPressed: () {
          Navigator.pop(context); // Close dialog
          Navigator.pop(context); // Close review screen
          archiveConversation();
        },
        child: Text('Done'),
      ),
    ],
  ),
);
```

## Complete Flutter Code Example

```dart
class ReviewScreen extends StatefulWidget {
  final String otherUserId;
  final String transactionId;
  final UserProfile otherUser;

  const ReviewScreen({
    required this.otherUserId,
    required this.transactionId,
    required this.otherUser,
  });

  @override
  _ReviewScreenState createState() => _ReviewScreenState();
}

class _ReviewScreenState extends State<ReviewScreen> {
  int rating = 0;
  TransactionStatus? selectedStatus;
  final TextEditingController reviewTextController = TextEditingController();
  bool isSubmitting = false;

  bool get isFormValid => rating > 0 && selectedStatus != null;

  Future<void> submitReview() async {
    if (!isFormValid) return;

    setState(() => isSubmitting = true);

    try {
      final response = await ApiClient.submitReview(
        transactionId: widget.transactionId,
        reviewerId: currentUserId,
        targetUserId: widget.otherUserId,
        rating: rating,
        reviewText: reviewTextController.text.isEmpty 
            ? null 
            : reviewTextController.text,
        transactionStatus: selectedStatus!.value,
      );

      if (response.success) {
        showSuccessDialog();
      } else {
        showErrorDialog(response.error);
      }
    } catch (e) {
      showErrorDialog(e.toString());
    } finally {
      setState(() => isSubmitting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('Review ${widget.otherUser.name}'),
      ),
      body: SingleChildScrollView(
        padding: EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _buildUserProfileCard(),
            SizedBox(height: 24),
            _buildRatingSection(),
            SizedBox(height: 24),
            _buildStatusSection(),
            SizedBox(height: 24),
            _buildReviewTextSection(),
            SizedBox(height: 24),
            _buildGuidelinesSection(),
            SizedBox(height: 32),
            _buildActionButtons(),
          ],
        ),
      ),
    );
  }

  // ... implement all the widget builders shown above
}
```

## API Endpoints to Implement

### New Required Endpoint

```kotlin
/**
 * Check if user can review another user
 */
fun Route.checkReviewEligibilityRoute() {
    val transactionDao: BarterTransactionDao by inject(BarterTransactionDao::class.java)
    val reviewDao: ReviewDao by inject(ReviewDao::class.java)
    val profileDao: UserProfileDaoImpl by inject(UserProfileDaoImpl::class.java)
    val authDao: AuthenticationDaoImpl by inject(AuthenticationDaoImpl::class.java)

    get("/api/v1/reviews/eligibility/{userId}/with/{otherUserId}") {
        val (authenticatedUserId, _) = verifyRequestSignature(call, authDao)
        if (authenticatedUserId == null) return@get

        val userId = call.parameters["userId"]
        val otherUserId = call.parameters["otherUserId"]

        if (userId != authenticatedUserId) {
            return@get call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "Can only check your own eligibility")
            )
        }

        try {
            // Find most recent completed transaction between users
            val transactions = transactionDao.getTransactionsBetweenUsers(userId!!, otherUserId!!)
            val completedTransaction = transactions
                .filter { it.status == TransactionStatus.DONE }
                .maxByOrNull { it.completedAt ?: it.initiatedAt }

            if (completedTransaction == null) {
                return@get call.respond(HttpStatusCode.OK, mapOf(
                    "eligible" to false,
                    "transactionId" to null,
                    "reason" to "No completed transaction found",
                    "otherUserName" to (profileDao.getProfile(otherUserId)?.name ?: "Unknown")
                ))
            }

            // Check if already reviewed
            val alreadyReviewed = reviewDao.hasAlreadyReviewed(
                userId,
                otherUserId,
                completedTransaction.id
            )

            if (alreadyReviewed) {
                return@get call.respond(HttpStatusCode.OK, mapOf(
                    "eligible" to false,
                    "transactionId" to completedTransaction.id,
                    "reason" to "You have already reviewed this transaction",
                    "otherUserName" to (profileDao.getProfile(otherUserId)?.name ?: "Unknown")
                ))
            }

            call.respond(HttpStatusCode.OK, mapOf(
                "eligible" to true,
                "transactionId" to completedTransaction.id,
                "reason" to null,
                "otherUserName" to (profileDao.getProfile(otherUserId)?.name ?: "Unknown"),
                "transactionCompletedAt" to completedTransaction.completedAt?.toEpochMilli()
            ))

        } catch (e: Exception) {
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Failed to check eligibility")
            )
        }
    }
}
```

## Summary

The review workflow:

1. **Trigger**: User swipes conversation to delete
2. **Check**: API call to check eligibility
3. **Prompt**: Show review screen if eligible
4. **Submit**: User fills form and submits
5. **Confirm**: Show success message
6. **Archive**: Archive conversation

**Key UX Principles**:
- ✅ Make reviewing **easy but optional**
- ✅ Show clear value ("helps community trust")
- ✅ **Blind review** explanation (both must submit)
- ✅ Clear consequences for scam reports
- ✅ Skip option always available
- ✅ Can review later from history

This creates a natural review point without being intrusive!
