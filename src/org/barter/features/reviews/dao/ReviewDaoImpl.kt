package org.barter.features.reviews.dao

import org.barter.extensions.DatabaseFactory.dbQuery
import org.barter.features.reviews.db.ReviewsTable
import org.barter.features.reviews.model.TransactionStatus
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import java.time.Instant
import java.time.temporal.ChronoUnit

class ReviewDaoImpl : ReviewDao {

    override suspend fun createReview(review: ReviewDto): Boolean = dbQuery {
        try {
            ReviewsTable.insert {
                it[id] = review.id
                it[transactionId] = review.transactionId
                it[reviewerId] = review.reviewerId
                it[targetUserId] = review.targetUserId
                it[rating] = review.rating
                it[reviewText] = review.reviewText
                it[transactionStatus] = review.transactionStatus.value
                it[reviewWeight] = review.reviewWeight.toBigDecimal()
                it[isVisible] = review.isVisible
                it[submittedAt] = review.submittedAt
                it[revealedAt] = review.revealedAt
                it[isVerified] = review.isVerified
                it[moderationStatus] = review.moderationStatus
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun getUserReviews(userId: String): List<ReviewDto> = dbQuery {
        ReviewsTable
            .selectAll()
            .where {
                (ReviewsTable.targetUserId eq userId) and
                (ReviewsTable.isVisible eq true)
            }
            .orderBy(ReviewsTable.submittedAt, SortOrder.DESC)
            .map { rowToDto(it) }
    }

    override suspend fun getTransactionReviews(transactionId: String): List<ReviewDto> = dbQuery {
        ReviewsTable
            .selectAll()
            .where {
                (ReviewsTable.transactionId eq transactionId) and
                (ReviewsTable.isVisible eq true)
            }
            .map { rowToDto(it) }
    }

    override suspend fun hasAlreadyReviewed(
        reviewerId: String,
        targetUserId: String,
        transactionId: String
    ): Boolean = dbQuery {
        ReviewsTable
            .selectAll()
            .where {
                (ReviewsTable.reviewerId eq reviewerId) and
                (ReviewsTable.targetUserId eq targetUserId) and
                (ReviewsTable.transactionId eq transactionId)
            }
            .count() > 0
    }

    override suspend fun getReviewsInLastDays(userId: String, days: Int): Int = dbQuery {
        val cutoffDate = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        ReviewsTable
            .selectAll()
            .where {
                (ReviewsTable.reviewerId eq userId) and
                (ReviewsTable.submittedAt greater cutoffDate)
            }
            .count()
            .toInt()
    }

    override suspend fun haveBothPartiesSubmitted(
        transactionId: String,
        user1Id: String,
        user2Id: String
    ): Boolean = dbQuery {
        // Check if user1 has submitted a review for user2
        val user1Reviewed = ReviewsTable
            .selectAll()
            .where {
                (ReviewsTable.transactionId eq transactionId) and
                (ReviewsTable.reviewerId eq user1Id) and
                (ReviewsTable.targetUserId eq user2Id)
            }
            .count() > 0

        // Check if user2 has submitted a review for user1
        val user2Reviewed = ReviewsTable
            .selectAll()
            .where {
                (ReviewsTable.transactionId eq transactionId) and
                (ReviewsTable.reviewerId eq user2Id) and
                (ReviewsTable.targetUserId eq user1Id)
            }
            .count() > 0

        user1Reviewed && user2Reviewed
    }

    override suspend fun makeReviewsVisible(transactionId: String): Boolean = dbQuery {
        try {
            val now = Instant.now()
            ReviewsTable.update({ ReviewsTable.transactionId eq transactionId }) {
                it[isVisible] = true
                it[revealedAt] = now
            } > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun updateModerationStatus(reviewId: String, status: String): Boolean = dbQuery {
        try {
            ReviewsTable.update({ ReviewsTable.id eq reviewId }) {
                it[moderationStatus] = status
            } > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun markAsVerified(reviewId: String): Boolean = dbQuery {
        try {
            ReviewsTable.update({ ReviewsTable.id eq reviewId }) {
                it[isVerified] = true
            } > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun getWeightedReviews(userId: String): List<WeightedReviewDto> = dbQuery {
        ReviewsTable
            .selectAll()
            .where {
                (ReviewsTable.targetUserId eq userId) and
                (ReviewsTable.isVisible eq true)
            }
            .map { row ->
                WeightedReviewDto(
                    rating = row[ReviewsTable.rating].toDouble(),
                    weight = row[ReviewsTable.reviewWeight].toDouble()
                )
            }
    }

    override suspend fun getVerifiedReviewCount(userId: String): Int = dbQuery {
        ReviewsTable
            .selectAll()
            .where {
                (ReviewsTable.targetUserId eq userId) and
                (ReviewsTable.isVerified eq true) and
                (ReviewsTable.isVisible eq true)
            }
            .count()
            .toInt()
    }

    override suspend fun deleteReview(reviewId: String): Boolean = dbQuery {
        try {
            ReviewsTable.deleteWhere { ReviewsTable.id eq reviewId } > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun updateReviewWeight(reviewId: String, weight: Double): Boolean = dbQuery {
        try {
            ReviewsTable.update({ ReviewsTable.id eq reviewId }) {
                it[reviewWeight] = weight.toBigDecimal()
            } > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun rowToDto(row: ResultRow): ReviewDto {
        return ReviewDto(
            id = row[ReviewsTable.id],
            transactionId = row[ReviewsTable.transactionId],
            reviewerId = row[ReviewsTable.reviewerId],
            targetUserId = row[ReviewsTable.targetUserId],
            rating = row[ReviewsTable.rating],
            reviewText = row[ReviewsTable.reviewText],
            transactionStatus = TransactionStatus.fromString(row[ReviewsTable.transactionStatus]) 
                ?: TransactionStatus.DONE,
            reviewWeight = row[ReviewsTable.reviewWeight].toDouble(),
            isVisible = row[ReviewsTable.isVisible],
            submittedAt = row[ReviewsTable.submittedAt],
            revealedAt = row[ReviewsTable.revealedAt],
            isVerified = row[ReviewsTable.isVerified],
            moderationStatus = row[ReviewsTable.moderationStatus]
        )
    }
}
