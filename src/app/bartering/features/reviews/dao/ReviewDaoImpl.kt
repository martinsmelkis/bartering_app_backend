package app.bartering.features.reviews.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.reviews.db.ReviewAppealsTable
import app.bartering.features.reviews.db.ReviewsTable
import app.bartering.features.reviews.model.AppealStatus
import app.bartering.features.reviews.model.EvidenceItem
import app.bartering.features.reviews.model.ReviewAppeal
import app.bartering.features.reviews.model.TransactionStatus
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

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

    override suspend fun getAverageRatingAndCount(userId: String): Pair<Double, Int>? = dbQuery {
        // Use raw SQL for aggregate query - more reliable with Exposed
        val query = """
            SELECT AVG(rating) as avg_rating, COUNT(*) as review_count 
            FROM user_reviews 
            WHERE target_user_id = ? AND is_visible = true
        """.trimIndent()
        
        val result = mutableListOf<Pair<Double, Int>>()
        (org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.current().connection.connection as java.sql.Connection)
            .prepareStatement(query)
            .also { statement ->
                statement.setString(1, userId)
                val rs = statement.executeQuery()
                if (rs.next()) {
                    val avgRating = (rs.getObject("avg_rating") as? Number)?.toDouble() ?: 0.0
                    val count = (rs.getObject("review_count") as? Number)?.toInt() ?: 0
                    if (count > 0) {
                        result.add(avgRating to count)
                    }
                }
            }
        result.firstOrNull()
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

    override suspend fun createAppeal(appeal: ReviewAppeal): Boolean = dbQuery {
        try {
            ReviewAppealsTable.insert {
                it[id] = appeal.id.ifBlank { UUID.randomUUID().toString() }
                it[reviewId] = appeal.reviewId
                it[appealedBy] = appeal.appealedBy
                it[reason] = appeal.reason
                it[status] = appeal.status.value
                it[appealedAt] = Instant.ofEpochMilli(appeal.appealedAt)
                it[resolvedAt] = appeal.resolvedAt?.let { ts -> Instant.ofEpochMilli(ts) }
                it[moderatorNotes] = buildEvidenceNotes(appeal.evidenceItems, appeal.moderatorNotes)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun getUserAppeals(userId: String): List<ReviewAppeal> = dbQuery {
        ReviewAppealsTable
            .selectAll()
            .where { ReviewAppealsTable.appealedBy eq userId }
            .orderBy(ReviewAppealsTable.appealedAt, SortOrder.DESC)
            .map { row -> rowToReviewAppeal(row) }
    }

    override suspend fun getRecentAppeals(limit: Int): List<ReviewAppeal> = dbQuery {
        ReviewAppealsTable
            .selectAll()
            .orderBy(ReviewAppealsTable.appealedAt, SortOrder.DESC)
            .limit(limit)
            .map { row -> rowToReviewAppeal(row) }
    }

    override suspend fun reviewExists(reviewId: String): Boolean = dbQuery {
        ReviewsTable
            .selectAll()
            .where { ReviewsTable.id eq reviewId }
            .count() > 0
    }

    private fun rowToReviewAppeal(row: ResultRow): ReviewAppeal {
        val notes = row[ReviewAppealsTable.moderatorNotes]
        val (evidenceItems, moderatorNotes) = parseEvidenceAndNotes(notes)

        return ReviewAppeal(
            id = row[ReviewAppealsTable.id],
            reviewId = row[ReviewAppealsTable.reviewId],
            appealedBy = row[ReviewAppealsTable.appealedBy],
            reason = row[ReviewAppealsTable.reason],
            evidenceItems = evidenceItems,
            status = AppealStatus.fromString(row[ReviewAppealsTable.status]) ?: AppealStatus.PENDING,
            appealedAt = row[ReviewAppealsTable.appealedAt].toEpochMilli(),
            resolvedAt = row[ReviewAppealsTable.resolvedAt]?.toEpochMilli(),
            moderatorNotes = moderatorNotes
        )
    }

    private fun buildEvidenceNotes(evidenceItems: List<EvidenceItem>, moderatorNotes: String?): String? {
        if (evidenceItems.isEmpty() && moderatorNotes.isNullOrBlank()) return moderatorNotes

        val evidenceBlob = if (evidenceItems.isEmpty()) {
            null
        } else {
            evidenceItems.joinToString(separator = "\n") { ev ->
                val desc = ev.description?.takeIf { it.isNotBlank() }?.let { " | desc=$it" } ?: ""
                "type=${ev.type} | ref=${ev.reference}$desc"
            }
        }

        return listOfNotNull(
            evidenceBlob?.let { "[user_evidence]\n$it" },
            moderatorNotes?.takeIf { it.isNotBlank() }?.let { "[moderator_notes]\n$it" }
        ).joinToString("\n\n").ifBlank { null }
    }

    private fun parseEvidenceAndNotes(storedNotes: String?): Pair<List<EvidenceItem>, String?> {
        if (storedNotes.isNullOrBlank()) return emptyList<EvidenceItem>() to null

        val evidence = mutableListOf<EvidenceItem>()
        val lines = storedNotes.lines()
        var inEvidence = false
        val moderator = StringBuilder()

        for (line in lines) {
            when {
                line.trim() == "[user_evidence]" -> {
                    inEvidence = true
                    continue
                }
                line.trim() == "[moderator_notes]" -> {
                    inEvidence = false
                    continue
                }
                inEvidence && line.contains("type=") && line.contains("ref=") -> {
                    val parts = line.split("|").map { it.trim() }
                    val type = parts.firstOrNull { it.startsWith("type=") }?.removePrefix("type=")
                    val ref = parts.firstOrNull { it.startsWith("ref=") }?.removePrefix("ref=")
                    val desc = parts.firstOrNull { it.startsWith("desc=") }?.removePrefix("desc=")
                    if (!type.isNullOrBlank() && !ref.isNullOrBlank()) {
                        evidence.add(EvidenceItem(type = type, reference = ref, description = desc))
                    }
                }
                !inEvidence -> {
                    if (moderator.isNotEmpty()) moderator.append("\n")
                    moderator.append(line)
                }
            }
        }

        val moderatorNotes = moderator.toString().trim().ifBlank { null }
        return evidence to moderatorNotes
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
