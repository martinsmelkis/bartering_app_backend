# Local File Storage Setup Guide

## Overview

This guide helps you set up local file storage for posting images. Images are stored on your
server's filesystem and served via HTTP.

## Quick Start (Development)

### 1. No Configuration Needed!

The local storage works out-of-the-box with defaults:

- **Storage directory**: `uploads/images/` (created automatically)
- **Base URL**: `/api/v1/images`

Just start your server and it works!

```bash
./gradlew run
```

You should see:

```
✅ Created image upload directory: /path/to/project/uploads/images
✅ Local file storage initialized: /path/to/project/uploads/images
   Images will be accessible at: /api/v1/images/{filename}
📁 Using image storage: LocalFileStorageService
```

### 2. Test Upload

```bash
curl -X POST http://localhost:8081/api/v1/postings/with-images \
  -F "userId=test-user" \
  -F "title=Test Posting" \
  -F "description=Test description" \
  -F "isOffer=true" \
  -F "timestamp=$(date +%s)000" \
  -F "signature=test-sig" \
  -F "images=@/path/to/image.jpg"
```

Response:

```json
{
  "id": "uuid",
  "imageUrls": [
    "/api/v1/images/test-user/abc-123.jpg"
  ]
}
```

### 3. View Image

Open in browser:

```
http://localhost:8081/api/v1/images/test-user/abc-123.jpg
```

## Production Setup

### Option 1: Default Location

Keep defaults, but ensure the directory is writable:

```bash
# Create directory
mkdir -p uploads/images

# Set permissions
chmod 755 uploads/images

# Set ownership (if running as specific user)
chown -R your-app-user:your-app-user uploads/
```

### Option 2: Custom Location

Use environment variables to customize:

```bash
# Set custom upload directory
export IMAGE_UPLOAD_DIR=/var/www/barter-app/images

# Set custom base URL (if using different path)
export IMAGE_BASE_URL=/api/v1/images

# Start server
./gradlew run
```

### Option 3: Docker

In `docker-compose.yml`:

```yaml
services:
  backend:
    image: barter-app-backend
    environment:
      - IMAGE_UPLOAD_DIR=/app/uploads/images
      - IMAGE_BASE_URL=/api/v1/images
      - IMAGE_STORAGE_TYPE=local
    volumes:
      - ./uploads:/app/uploads  # Persist images outside container
```

## File Structure

```
uploads/
└── images/
    ├── user-id-1/
    │   ├── abc-123-456.jpg
    │   ├── def-789-012.jpg
    │   └── ghi-345-678.png
    ├── user-id-2/
    │   ├── jkl-901-234.jpg
    │   └── mno-567-890.jpg
    └── user-id-3/
        └── pqr-012-345.jpg
```

- Each user has their own subdirectory
- Filenames are UUIDs to prevent conflicts
- Original file extension is preserved

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `IMAGE_STORAGE_TYPE` | `local` | Storage type: `local` or `firebase` |
| `IMAGE_UPLOAD_DIR` | `uploads/images` | Directory for storing images |
| `IMAGE_BASE_URL` | `/api/v1/images` | Base URL for serving images |

## API Endpoints

### Upload Images (Create Posting)

```
POST /api/v1/postings/with-images
Content-Type: multipart/form-data
```

**Form Fields:**

- `userId` (required)
- `title` (required)
- `description` (required)
- `isOffer` (required)
- `timestamp` (required)
- `signature` (required)
- `images` (optional) - Array of image files

**Response:**

```json
{
  "id": "posting-id",
  "userId": "user-id",
  "title": "My Bicycle",
  "imageUrls": [
    "/api/v1/images/user-id/abc-123.jpg",
    "/api/v1/images/user-id/def-456.jpg"
  ]
}
```

### Serve Image

```
GET /api/v1/images/{userId}/{fileName}
```

Returns the image file with appropriate content-type and cache headers.

### Storage Stats

```
GET /api/v1/images/stats
```

Returns storage statistics:

```json
{
  "totalFiles": 1523,
  "totalSizeMB": 3842,
  "uploadDirectory": "/var/www/barter-app/uploads/images"
}
```

## Backup Strategy

### Manual Backup

```bash
#!/bin/bash
# backup-images.sh

BACKUP_DIR="/backups/images"
SOURCE_DIR="uploads/images"
DATE=$(date +%Y%m%d)

# Create timestamped backup
tar -czf "$BACKUP_DIR/images-$DATE.tar.gz" "$SOURCE_DIR"

# Keep only last 30 days
find "$BACKUP_DIR" -name "images-*.tar.gz" -mtime +30 -delete

echo "Backup completed: $BACKUP_DIR/images-$DATE.tar.gz"
```

Make executable and run daily:

```bash
chmod +x backup-images.sh
crontab -e
# Add line:
0 2 * * * /path/to/backup-images.sh
```

### Rsync to Remote Server

```bash
#!/bin/bash
# sync-images.sh

rsync -avz --progress \
  uploads/images/ \
  user@backup-server:/backups/barter-images/

echo "Sync completed"
```

## Disk Space Management

### Check Usage

```bash
# Check total usage
du -sh uploads/images/

# Check per user
du -sh uploads/images/*/ | sort -h

# Find largest files
find uploads/images -type f -exec du -h {} + | sort -rh | head -20
```

### Clean Old Images

```bash
# Delete images older than 90 days from deleted postings
# (Implement in application logic, not filesystem)

# Check for orphaned images
# (Images not referenced in database)
```

### Monitor Disk Space

```bash
# Alert if disk usage > 80%
USAGE=$(df -h uploads/ | awk 'NR==2 {print $5}' | sed 's/%//')
if [ "$USAGE" -gt 80 ]; then
  echo "WARNING: Disk usage at $USAGE%"
  # Send alert
fi
```

## Performance Optimization

### 1. Enable Gzip Compression (Nginx)

```nginx
# /etc/nginx/nginx.conf
gzip on;
gzip_types image/jpeg image/png image/gif;
gzip_min_length 1000;
```

### 2. Add Caching (Nginx)

```nginx
location /api/v1/images/ {
    proxy_pass http://localhost:8081;
    
    # Cache images for 1 year
    expires 1y;
    add_header Cache-Control "public, immutable";
    
    # Enable client-side caching
    add_header ETag $upstream_http_etag;
}
```

### 3. Use CDN (Optional)

```nginx
# Serve images directly from Nginx (bypass Ktor)
location /api/v1/images/ {
    alias /var/www/barter-app/uploads/images/;
    expires 1y;
    add_header Cache-Control "public, immutable";
}
```

This is much faster than serving through Ktor!

## Security

### 1. File Upload Validation

Already implemented in code:

- ✅ Max file size: 10MB
- ✅ Content type validation
- ✅ UUID filenames (prevent path traversal)
- ✅ User-specific directories

### 2. Directory Permissions

```bash
# Restrictive permissions
chmod 755 uploads/images/
chmod 644 uploads/images/**/*.jpg

# Only app user can write
chown app-user:app-user uploads/images/
```

### 3. Prevent Direct Access (if behind proxy)

```nginx
# Block direct access to upload directory
location ~ ^/uploads/ {
    deny all;
    return 404;
}

# Only allow through API
location /api/v1/images/ {
    proxy_pass http://localhost:8081;
}
```

## Migration to Cloud Storage

When you're ready to migrate to Firebase/S3:

### 1. Set Environment Variable

```bash
# Switch to Firebase
export IMAGE_STORAGE_TYPE=firebase

# Configure Firebase
export FIREBASE_SERVICE_ACCOUNT_KEY=/path/to/firebase-key.json
export FIREBASE_STORAGE_BUCKET=your-app.appspot.com

# Restart server
```

No code changes needed!

### 2. Migrate Existing Images

```kotlin
// Run migration script
suspend fun migrateToFirebase() {
    val localStorage = LocalFileStorageService()
    val firebaseStorage = FirebaseStorageService()
    val postingDao = // inject
    
    val allPostings = postingDao.getAllPostings()
    
    allPostings.forEach { posting ->
        val newUrls = posting.imageUrls.map { localUrl ->
            // Get file from local storage
            val file = localStorage.getFile(localUrl)
            if (file != null && file.exists()) {
                // Upload to Firebase
                val bytes = file.readBytes()
                val newUrl = firebaseStorage.uploadImage(
                    imageData = bytes,
                    userId = posting.userId,
                    fileName = file.name,
                    contentType = "image/jpeg"
                )
                
                // Delete local file after successful upload
                localStorage.deleteImage(localUrl)
                
                newUrl
            } else {
                localUrl // Keep original if file not found
            }
        }
        
        // Update posting with new URLs
        postingDao.updatePostingImages(posting.id, newUrls)
        
        println("Migrated posting ${posting.id}: ${posting.imageUrls.size} images")
    }
}
```

### 3. Verify Migration

1. Check all images load in app
2. Verify Firebase Storage dashboard shows files
3. Test new uploads go to Firebase
4. Clean up local files after verification

## Troubleshooting

### Images not loading

**Check:**

```bash
# Directory exists?
ls -la uploads/images/

# Files exist?
ls -la uploads/images/user-id/

# Permissions correct?
stat uploads/images/
```

### "Directory not writable" error

```bash
# Fix permissions
chmod 755 uploads/images/
chown -R app-user:app-user uploads/
```

### Images disappear after restart (Docker)

**Cause**: No volume mounted

**Fix**: Add volume to docker-compose.yml:

```yaml
volumes:
  - ./uploads:/app/uploads
```

### Disk full error

```bash
# Check disk space
df -h

# Find large files
du -sh uploads/images/*/ | sort -h

# Clean up if needed
```

## Costs

### Local Storage

- **Storage**: FREE (uses existing server disk)
- **Bandwidth**: FREE (uses existing server bandwidth)
- **Total**: $0/month extra

### vs Cloud Storage

- Firebase: $63/month for 100GB + 500GB bandwidth
- AWS S3: $90/month for same
- **Savings**: $63-90/month

## Summary

✅ **Pros:**

- Zero cost
- Simple setup
- Full control
- Works offline
- Easy debugging

❌ **Cons:**

- No CDN (slower for distant users)
- Manual backups
- Limited scalability
- No auto-optimization

**Best for:**

- MVP/prototype
- < 1,000 users
- Regional apps
- Budget < $50/month

**Migrate to cloud when:**

- > 5,000 users
- Global user base
- Storage > 500GB
- Performance issues

See `LOCAL_VS_CLOUD_STORAGE_ANALYSIS.md` for detailed comparison.
