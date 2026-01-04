package org.barter.features.reviews.dao

import org.barter.extensions.DatabaseFactory.dbQuery
import org.barter.features.reviews.db.ReputationBadgesTable
import org.barter.features.reviews.db.ReputationsTable
import org.barter.features.reviews.model.ReputationBadge
import org.barter.features.reviews.model.TrustLevel
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant

class ReputationDaoImpl : ReputationDao {

    override suspend fun getReputation(userId: String): ReputationDto? = dbQuery {
        ReputationsTable
            .selectAll()
            .where { ReputationsTable.userId eq userId }
            .map { rowToDto(it) }
            .singleOrNull()
    }

    override suspend fun updateReputation(reputation: ReputationDto): Boolean = dbQuery {
        try {
            val existing = ReputationsTable
                .selectAll()
                .where { ReputationsTable.userId eq reputation.userId }
                .count() > 0

            if (existing) {
                ReputationsTable.update({ ReputationsTable.userId eq reputation.userId }) {
                    it[averageRating] = reputation.averageRating.toBigDecimal()
                    it[totalReviews] = reputation.totalReviews
                    it[verifiedReviews] = reputation.verifiedReviews
                    it[tradeDiversityScore] = reputation.tradeDiversityScore.toBigDecimal()
                    it[trustLevel] = reputation.trustLevel.value
                    it[lastUpdated] = reputation.lastUpdated
                } > 0
            } else {
                ReputationsTable.insert {
                    it[userId] = reputation.userId
                    it[averageRating] = reputation.averageRating.toBigDecimal()
                    it[totalReviews] = reputation.totalReviews
                    it[verifiedReviews] = reputation.verifiedReviews
                    it[tradeDiversityScore] = reputation.tradeDiversityScore.toBigDecimal()
                    it[trustLevel] = reputation.trustLevel.value
                    it[lastUpdated] = reputation.lastUpdated
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun getUserBadges(userId: String): List<ReputationBadge> = dbQuery {
        ReputationBadgesTable
            .selectAll()
            .where { ReputationBadgesTable.userId eq userId }
            .mapNotNull { row ->
                ReputationBadge.fromString(row[ReputationBadgesTable.badgeType])
            }
    }

    override suspend fun addBadge(userId: String, badge: ReputationBadge): Boolean = dbQuery {
        try {
            ReputationBadgesTable.insertIgnore {
                it[ReputationBadgesTable.userId] = userId
                it[badgeType] = badge.value
            }.insertedCount > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun removeBadge(userId: String, badge: ReputationBadge): Boolean = dbQuery {
        try {
            ReputationBadgesTable.deleteWhere {
                (ReputationBadgesTable.userId eq userId) and
                (badgeType eq badge.value)
            } > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun hasBadge(userId: String, badge: ReputationBadge): Boolean = dbQuery {
        ReputationBadgesTable
            .selectAll()
            .where {
                (ReputationBadgesTable.userId eq userId) and
                (ReputationBadgesTable.badgeType eq badge.value)
            }
            .count() > 0
    }

    private fun rowToDto(row: ResultRow): ReputationDto {
        return ReputationDto(
            userId = row[ReputationsTable.userId],
            averageRating = row[ReputationsTable.averageRating].toDouble(),
            totalReviews = row[ReputationsTable.totalReviews],
            verifiedReviews = row[ReputationsTable.verifiedReviews],
            tradeDiversityScore = row[ReputationsTable.tradeDiversityScore].toDouble(),
            trustLevel = TrustLevel.fromString(row[ReputationsTable.trustLevel]) ?: TrustLevel.NEW,
            lastUpdated = row[ReputationsTable.lastUpdated]
        )
    }
}
