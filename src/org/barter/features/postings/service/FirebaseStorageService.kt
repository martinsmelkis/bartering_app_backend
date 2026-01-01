package org.barter.features.postings.service

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.*
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.StorageClient
import java.io.FileInputStream
import java.util.*

/**
 * Firebase Storage implementation.
 * Stores images in Google Firebase Cloud Storage.
 *
 * Configuration:
 * - Set FIREBASE_SERVICE_ACCOUNT_KEY environment variable to path of service account JSON
 * - Set FIREBASE_STORAGE_BUCKET environment variable to your bucket name (e.g., "your-app.appspot.com")
 */
class FirebaseStorageService : ImageStorageService {

    private var bucketName: String = ""
    private var initialized: Boolean = false

    init {
        // Initialize Firebase Admin SDK
        try {
            // Try to load service account key from environment variable or file
            val serviceAccountPath = System.getenv("FIREBASE_SERVICE_ACCOUNT_KEY")
                ?: "firebase-service-account.json"

            val serviceAccount = FileInputStream(serviceAccountPath)

            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .setStorageBucket(
                    System.getenv("FIREBASE_STORAGE_BUCKET") ?: "your-app.appspot.com"
                )
                .build()

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options)
                println("✅ Firebase Admin SDK initialized successfully")
            }

            bucketName = System.getenv("FIREBASE_STORAGE_BUCKET") ?: "your-app.appspot.com"
            initialized = true
        } catch (e: Exception) {
            println("⚠️  Firebase initialization failed: ${e.message}")
            println("   Image uploads will not work until Firebase is configured")
            println("   See FIREBASE_SETUP_GUIDE.md for configuration instructions")
            initialized = false
        }
    }

    override fun isInitialized(): Boolean = initialized

    /**
     * Upload an image to Firebase Storage
     *
     * @param imageData The image bytes
     * @param userId The user ID (for folder organization)
     * @param fileName The original filename
     * @param contentType The MIME type (e.g., "image/jpeg")
     * @return The public download URL
     */
    override suspend fun uploadImage(
        imageData: ByteArray,
        userId: String,
        fileName: String,
        contentType: String
    ): String {
        if (!isInitialized()) {
            throw IllegalStateException("Firebase Storage is not initialized. Check configuration.")
        }

        try {
            val bucket = StorageClient.getInstance().bucket()

            // Generate unique filename
            val extension = fileName.substringAfterLast(".", "jpg")
            val uniqueFileName = "postings/$userId/${UUID.randomUUID()}.$extension"

            // Create blob with public read access
            val blobId = BlobId.of(bucket.name, uniqueFileName)
            val blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType)
                .build()

            // Get storage service
            val storage = StorageOptions.newBuilder()
                .setCredentials(GoogleCredentials.getApplicationDefault())
                .build()
                .service

            // Upload the file
            storage.create(blobInfo, imageData)

            // Make the file publicly accessible
            val acl = Acl.of(Acl.User.ofAllUsers(), Acl.Role.READER)
            storage.createAcl(blobId, acl)

            // Return public URL
            val publicUrl = "https://storage.googleapis.com/${bucket.name}/$uniqueFileName"

            println("✅ Uploaded image: $publicUrl")
            return publicUrl

        } catch (e: Exception) {
            println("❌ Failed to upload image: ${e.message}")
            e.printStackTrace()
            throw Exception("Failed to upload image to Firebase Storage: ${e.message}")
        }
    }

    /**
     * Delete an image from Firebase Storage
     *
     * @param imageUrl The full URL of the image to delete
     * @return true if deletion was successful
     */
    override suspend fun deleteImage(imageUrl: String): Boolean {
        if (!isInitialized()) {
            println("⚠️  Firebase Storage is not initialized. Cannot delete image.")
            return false
        }

        try {
            val bucket = StorageClient.getInstance().bucket()

            // Extract the file path from the URL
            // URL format: https://storage.googleapis.com/bucket-name/postings/userId/filename.jpg
            val filePath = imageUrl.substringAfter("${bucket.name}/")

            val blob = bucket.get(filePath)
            if (blob != null && blob.exists()) {
                blob.delete()
                println("✅ Deleted image: $imageUrl")
                return true
            } else {
                println("⚠️  Image not found: $imageUrl")
                return false
            }

        } catch (e: Exception) {
            println("❌ Failed to delete image: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

}
