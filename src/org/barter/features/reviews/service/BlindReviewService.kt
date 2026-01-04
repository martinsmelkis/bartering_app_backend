package org.barter.features.reviews.service

import java.time.Duration
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * Service for managing blind review submissions.
 * Reviews are encrypted and hidden until both parties submit or deadline expires.
 * This prevents reciprocal review manipulation ("I'll give you 5 stars if you give me 5 stars").
 */
class BlindReviewService {

    companion object {
        private const val ALGORITHM = "AES"
        private const val REVIEW_DEADLINE_DAYS = 14L
    }

    /**
     * Encrypts a review for blind submission.
     */
    fun encryptReview(reviewJson: String, secretKey: SecretKey): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encryptedBytes = cipher.doFinal(reviewJson.toByteArray())
        return Base64.getEncoder().encodeToString(encryptedBytes)
    }

    /**
     * Decrypts a blind review.
     */
    fun decryptReview(encryptedReview: String, secretKey: SecretKey): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        val decodedBytes = Base64.getDecoder().decode(encryptedReview)
        val decryptedBytes = cipher.doFinal(decodedBytes)
        return String(decryptedBytes)
    }

    /**
     * Generates a secret key for encrypting reviews.
     */
    fun generateSecretKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(ALGORITHM)
        keyGenerator.init(256)
        return keyGenerator.generateKey()
    }

    /**
     * Converts a secret key to a storable string.
     */
    fun secretKeyToString(key: SecretKey): String {
        return Base64.getEncoder().encodeToString(key.encoded)
    }

    /**
     * Converts a string back to a secret key.
     */
    fun stringToSecretKey(keyString: String): SecretKey {
        val decodedKey = Base64.getDecoder().decode(keyString)
        return SecretKeySpec(decodedKey, 0, decodedKey.size, ALGORITHM)
    }

    /**
     * Calculates the reveal deadline for a transaction.
     */
    fun calculateRevealDeadline(firstSubmissionTime: Instant): Instant {
        return firstSubmissionTime.plus(Duration.ofDays(REVIEW_DEADLINE_DAYS))
    }

    /**
     * Checks if it's time to reveal reviews (both submitted or deadline passed).
     */
    fun shouldRevealReviews(
        bothSubmitted: Boolean,
        revealDeadline: Instant
    ): Boolean {
        return bothSubmitted || Instant.now().isAfter(revealDeadline)
    }
}
