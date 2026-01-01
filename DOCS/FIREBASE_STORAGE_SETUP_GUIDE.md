# Firebase Storage Setup Guide

This guide will help you set up Firebase Storage for image uploads in your Barter app backend.

## Prerequisites

- A Firebase project (create one at [firebase.google.com](https://firebase.google.com))
- Firebase Storage enabled in your project
- Service account credentials

## Step 1: Create Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Click "Add project" or select existing project
3. Follow the setup wizard
4. Once created, note your **Project ID**

## Step 2: Enable Firebase Storage

1. In Firebase Console, go to **Build** → **Storage**
2. Click **Get Started**
3. Choose **Start in production mode** (or test mode for development)
4. Select a Cloud Storage location (choose closest to your users)
5. Click **Done**

Your storage bucket will be named: `your-project-id.appspot.com`

## Step 3: Configure Storage Rules

For production, update your Storage Rules:

```javascript
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    // Postings images - anyone can read, only authenticated users can write to their folder
    match /postings/{userId}/{imageId} {
      allow read: if true;  // Public read
      allow write: if request.auth != null && request.auth.uid == userId;  // User can only write to own folder
      allow delete: if request.auth != null && request.auth.uid == userId;  // User can only delete own images
    }
  }
}
```

For development/testing, you can use:

```javascript
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /{allPaths=**} {
      allow read, write: if true;  // WARNING: Only for testing!
    }
  }
}
```

## Step 4: Generate Service Account Key

1. Go to **Project Settings** (gear icon) → **Service Accounts**
2. Click **Generate New Private Key**
3. Click **Generate Key** - this downloads a JSON file
4. **IMPORTANT**: Keep this file secure! Never commit to Git!
5. Save it as `firebase-service-account.json` in your project root (already in `.gitignore`)

The JSON file looks like:

```json
{
  "type": "service_account",
  "project_id": "your-project-id",
  "private_key_id": "...",
  "private_key": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n",
  "client_email": "firebase-adminsdk-xxxxx@your-project-id.iam.gserviceaccount.com",
  "client_id": "...",
  "auth_uri": "https://accounts.google.com/o/oauth2/auth",
  "token_uri": "https://oauth2.googleapis.com/token",
  ...
}
```

## Step 5: Configure Environment Variables

### Development (Local)

Create or update `.env` file in project root:

```bash
FIREBASE_SERVICE_ACCOUNT_KEY=firebase-service-account.json
FIREBASE_STORAGE_BUCKET=your-project-id.appspot.com
```

### Production (Docker/Server)

Add to your `docker-compose.yml` or set as environment variables:

```yaml
environment:
  - FIREBASE_SERVICE_ACCOUNT_KEY=/app/firebase-service-account.json
  - FIREBASE_STORAGE_BUCKET=your-project-id.appspot.com
```

Or export directly:

```bash
export FIREBASE_SERVICE_ACCOUNT_KEY=/path/to/firebase-service-account.json
export FIREBASE_STORAGE_BUCKET=your-project-id.appspot.com
```

## Step 6: Verify Setup

1. Start your backend server
2. Check logs for: `✅ Firebase Admin SDK initialized successfully`
3. If you see errors, check:
    - Service account JSON file exists at specified path
    - File has correct permissions (readable)
    - Bucket name is correct (no `https://` or trailing slash)

## API Usage

### Create Posting with Images (Flutter Client)

```dart
import 'package:dio/dio.dart';
import 'package:http_parser/http_parser.dart';

Future<void> createPostingWithImages({
  required String userId,
  required String title,
  required String description,
  required bool isOffer,
  required List<File> images,
  String? timestamp,
  String? signature,
}) async {
  final dio = Dio();
  
  // Create multipart form data
  final formData = FormData.fromMap({
    'userId': userId,
    'title': title,
    'description': description,
    'isOffer': isOffer.toString(),
    'timestamp': timestamp ?? DateTime.now().millisecondsSinceEpoch.toString(),
    'signature': signature ?? 'dev-signature',
    
    // Add images
    'images': await Future.wait(
      images.map((file) async =>
        await MultipartFile.fromFile(
          file.path,
          contentType: MediaType('image', 'jpeg'),
        ),
      ),
    ),
  });
  
  try {
    final response = await dio.post(
      'http://your-server:8081/api/v1/postings/with-images',
      data: formData,
    );
    
    print('Posting created: ${response.data}');
  } catch (e) {
    print('Error creating posting: $e');
  }
}
```

### Example with Retrofit (your current setup)

Update your API interface:

```dart
@RestApi(baseUrl: "http://your-server:8081/api/v1")
abstract class PostingApi {
  factory PostingApi(Dio dio) = _PostingApi;

  @POST('/postings/with-images')
  @MultiPart()
  Future<UserPostingData?> createPostingWithImages(
    @Part(name: 'userId') String userId,
    @Part(name: 'title') String title,
    @Part(name: 'description') String description,
    @Part(name: 'isOffer') String isOffer,
    @Part(name: 'timestamp') String timestamp,
    @Part(name: 'signature') String signature,
    @Part(name: 'value') String? value,
    @Part(name: 'expiresAt') String? expiresAt,
    @Part(name: 'images') List<MultipartFile>? images,
  );
}
```

## Costs

Firebase Storage pricing (as of 2024):

### Free Tier (Spark Plan)

- **Storage**: 5 GB
- **Downloads**: 1 GB/day (≈30 GB/month)
- **Uploads**: 20K/day
- **Good for**: Development and small apps

### Paid Tier (Blaze Plan)

- **Storage**: $0.026/GB/month
- **Downloads**: $0.12/GB
- **Uploads**: $0.05/GB

### Example Costs

**Scenario**: 10,000 users, 5 photos each

- **Storage**: 50,000 photos × 2MB = 100GB = **$2.60/month**
- **Downloads**: 500GB/month = **$60/month**
- **Uploads**: 10GB/month = **$0.50/month**
- **Total**: ~**$63/month**

If you stay under free tier limits: **$0/month**

## Security Best Practices

1. **Never commit service account JSON to Git**
   ```bash
   # Add to .gitignore
   firebase-service-account.json
   *.json
   .env
   ```

2. **Use environment variables** - Don't hardcode credentials

3. **Rotate keys regularly** - Generate new service account keys periodically

4. **Limit bucket access** - Use Firebase Storage Rules to restrict access

5. **Validate file types** - Only allow images (JPEG, PNG, WebP)

6. **Limit file sizes** - Set max upload size (e.g., 10MB per image)

7. **Rate limiting** - Prevent abuse by limiting uploads per user

## Monitoring

### Check Storage Usage

Firebase Console → Storage → Usage tab

Monitor:

- Total storage used
- Bandwidth consumed
- Number of files
- Costs

### Set Budget Alerts

1. Go to Google Cloud Console → Billing
2. Create budget alert
3. Set alert at 50%, 90%, 100% of budget

## Troubleshooting

### Error: "Firebase not initialized"

**Cause**: Service account file not found or invalid

**Solution**:

```bash
# Check file exists
ls -la firebase-service-account.json

# Check environment variable
echo $FIREBASE_SERVICE_ACCOUNT_KEY

# Verify JSON is valid
cat firebase-service-account.json | json_pp
```

### Error: "Permission denied"

**Cause**: Storage Rules blocking access

**Solution**: Update Storage Rules in Firebase Console

### Error: "Bucket not found"

**Cause**: Wrong bucket name

**Solution**: Verify bucket name matches `project-id.appspot.com`

### Images not loading in app

**Cause**: Images not publicly accessible

**Solution**:

1. Check Storage Rules allow read
2. Verify ACL is set to public (done automatically in code)
3. Test URL in browser: `https://storage.googleapis.com/bucket-name/postings/userId/imageId.jpg`

## Migration from Base64

If you have existing base64 images in database:

```kotlin
suspend fun migrateBase64ToFirebase() {
    val firebaseStorage = FirebaseStorageService()
    val postingDao = // inject
    
    val postings = postingDao.getAllPostings()
    
    postings.forEach { posting ->
        val newImageUrls = posting.imageUrls.map { url ->
            if (url.startsWith("data:image")) {
                // Extract base64 data
                val base64Data = url.substringAfter("base64,")
                val imageBytes = Base64.getDecoder().decode(base64Data)
                
                // Upload to Firebase
                firebaseStorage.uploadImage(
                    imageData = imageBytes,
                    userId = posting.userId,
                    fileName = "migrated_${UUID.randomUUID()}.jpg",
                    contentType = "image/jpeg"
                )
            } else {
                url
            }
        }
        
        // Update posting with new URLs
        postingDao.updatePostingImageUrls(posting.id, newImageUrls)
    }
}
```

## Alternative: Self-Hosted Storage

If you prefer not to use Firebase, see `IMAGE_UPLOAD_GUIDE.md` for:

- Local file storage
- AWS S3
- Backblaze B2
- Other options

## Support

- [Firebase Storage Documentation](https://firebase.google.com/docs/storage)
- [Firebase Admin SDK Documentation](https://firebase.google.com/docs/admin/setup)
- [Pricing Calculator](https://firebase.google.com/pricing)
