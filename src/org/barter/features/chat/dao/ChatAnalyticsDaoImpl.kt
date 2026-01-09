package org.barter.features.chat.dao

import org.barter.extensions.DatabaseFactory.dbQuery
import org.barter.features.chat.db.ChatResponseTimesTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

class ChatAnalyticsDaoImpl : ChatAnalyticsDao {

    override suspend fun recordResponseTime(
        userId: String,
        conversationPartnerId: String,
        messageReceivedAt: Instant,
        responseSentAt: Instant
    ): Boolean = dbQuery {
        try {
            // Calculate response time in hours
            val durationMillis = responseSentAt.toEpochMilli() - messageReceivedAt.toEpochMilli()
            val durationHours = durationMillis / (1000.0 * 60 * 60)

            ChatResponseTimesTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[ChatResponseTimesTable.userId] = userId
                it[ChatResponseTimesTable.conversationPartnerId] = conversationPartnerId
                it[ChatResponseTimesTable.messageReceivedAt] = messageReceivedAt
                it[ChatResponseTimesTable.responseSentAt] = responseSentAt
                it[responseTimeHours] = BigDecimal.valueOf(durationHours)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun getAverageResponseTime(userId: String, daysToConsider: Int): Long? = dbQuery {
        val cutoffDate = Instant.now().minus(Duration.ofDays(daysToConsider.toLong()))

        val responseTimes = ChatResponseTimesTable
            .select(ChatResponseTimesTable.responseTimeHours)
            .where {
                (ChatResponseTimesTable.userId eq userId) and
                (ChatResponseTimesTable.createdAt greater cutoffDate)
            }
            .map { it[ChatResponseTimesTable.responseTimeHours].toDouble() }

        if (responseTimes.isEmpty()) {
            null
        } else {
            responseTimes.average().toLong()
        }
    }

    override suspend fun deleteOldResponseTimes(olderThanDays: Int): Int = dbQuery {
        val cutoffDate = Instant.now().minus(Duration.ofDays(olderThanDays.toLong()))

        ChatResponseTimesTable.deleteWhere {
            ChatResponseTimesTable.createdAt less cutoffDate
        }
    }
}
