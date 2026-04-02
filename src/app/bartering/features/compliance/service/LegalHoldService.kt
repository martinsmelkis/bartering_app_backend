package app.bartering.features.compliance.service

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.compliance.db.LegalHoldsTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant

class LegalHoldService {
    suspend fun applyHold(
        userId: String,
        reason: String,
        scope: String,
        imposedBy: String,
        expiresAt: Instant?
    ): Long = dbQuery {
        val now = Instant.now()
        LegalHoldsTable.insert {
            it[LegalHoldsTable.userId] = userId
            it[LegalHoldsTable.reason] = reason
            it[LegalHoldsTable.scope] = scope
            it[LegalHoldsTable.imposedBy] = imposedBy
            it[LegalHoldsTable.imposedAt] = now
            it[LegalHoldsTable.expiresAt] = expiresAt
            it[LegalHoldsTable.isActive] = true
            it[LegalHoldsTable.createdAt] = now
            it[LegalHoldsTable.updatedAt] = now
        }[LegalHoldsTable.id]
    }

    suspend fun releaseHold(
        holdId: Long,
        releasedBy: String,
        releaseReason: String?
    ): Boolean = dbQuery {
        val now = Instant.now()
        LegalHoldsTable.update({
            (LegalHoldsTable.id eq holdId) and (LegalHoldsTable.isActive eq true)
        }) {
            it[isActive] = false
            it[LegalHoldsTable.releasedAt] = now
            it[LegalHoldsTable.releasedBy] = releasedBy
            it[LegalHoldsTable.releaseReason] = releaseReason
            it[updatedAt] = now
        } > 0
    }

    suspend fun hasActiveHold(userId: String, requiredScope: String = "all"): Boolean = dbQuery {
        val now = Instant.now()
        val scopeFilter = if (requiredScope == "all") {
            (LegalHoldsTable.scope eq "all")
        } else {
            (LegalHoldsTable.scope eq "all") or (LegalHoldsTable.scope eq requiredScope)
        }

        LegalHoldsTable
            .selectAll()
            .where {
                (LegalHoldsTable.userId eq userId) and
                    (LegalHoldsTable.isActive eq true) and
                    scopeFilter and
                    ((LegalHoldsTable.expiresAt.isNull()) or (LegalHoldsTable.expiresAt greaterEq now))
            }
            .limit(1)
            .count() > 0
    }

    suspend fun getActiveHeldUserIds(): Set<String> = dbQuery {
        val now = Instant.now()
        LegalHoldsTable
            .selectAll()
            .where {
                (LegalHoldsTable.isActive eq true) and
                    ((LegalHoldsTable.expiresAt.isNull()) or (LegalHoldsTable.expiresAt greaterEq now))
            }
            .map { it[LegalHoldsTable.userId] }
            .toSet()
    }

    suspend fun listActiveHolds(userId: String? = null): List<LegalHoldView> = dbQuery {
        val now = Instant.now()
        LegalHoldsTable
            .selectAll()
            .where {
                val activeFilter = (LegalHoldsTable.isActive eq true) and
                    ((LegalHoldsTable.expiresAt.isNull()) or (LegalHoldsTable.expiresAt greaterEq now))
                if (userId == null) {
                    activeFilter
                } else {
                    activeFilter and (LegalHoldsTable.userId eq userId)
                }
            }
            .orderBy(LegalHoldsTable.imposedAt to SortOrder.DESC)
            .map {
                LegalHoldView(
                    id = it[LegalHoldsTable.id],
                    userId = it[LegalHoldsTable.userId],
                    reason = it[LegalHoldsTable.reason],
                    scope = it[LegalHoldsTable.scope],
                    imposedBy = it[LegalHoldsTable.imposedBy],
                    imposedAt = it[LegalHoldsTable.imposedAt],
                    expiresAt = it[LegalHoldsTable.expiresAt]
                )
            }
    }
}

data class LegalHoldView(
    val id: Long,
    val userId: String,
    val reason: String,
    val scope: String,
    val imposedBy: String,
    val imposedAt: Instant,
    val expiresAt: Instant?
)
