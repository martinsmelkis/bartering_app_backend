package app.bartering.features.chat.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.chat.db.OfflineMessagesTable
import app.bartering.features.chat.model.OfflineMessageDto
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Implementation of OfflineMessageDao for database operations
 */
class OfflineMessageDaoImpl : OfflineMessageDao {

    override suspend fun storeOfflineMessage(message: OfflineMessageDto): Boolean = dbQuery {
        try {
            OfflineMessagesTable.insert {
                it[id] = message.id
                it[senderId] = message.senderId
                it[recipientId] = message.recipientId
                it[senderName] = message.senderName
                it[encryptedPayload] = message.encryptedPayload
                it[senderPublicKey] = message.senderPublicKey
                it[timestamp] = Instant.ofEpochMilli(message.timestamp)
                it[delivered] = false
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun getPendingMessages(userId: String): List<OfflineMessageDto> = dbQuery {
        OfflineMessagesTable
            .selectAll()
            .where {
                (OfflineMessagesTable.recipientId eq userId) and
                        (OfflineMessagesTable.delivered eq false)
            }
            .orderBy(OfflineMessagesTable.timestamp)
            .map { rowToOfflineMessageDto(it) }
    }

    override suspend fun markAsDelivered(messageId: String): Boolean = dbQuery {
        try {
            OfflineMessagesTable.update({ OfflineMessagesTable.id eq messageId }) {
                it[delivered] = true
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun deleteDeliveredMessages(olderThanDays: Int): Int = dbQuery {
        val cutoffDate = Instant.now().minus(olderThanDays.toLong(), ChronoUnit.DAYS)
        OfflineMessagesTable.deleteWhere {
            (OfflineMessagesTable.delivered eq true) and (OfflineMessagesTable.timestamp less cutoffDate)
        }
    }

    private fun rowToOfflineMessageDto(row: ResultRow): OfflineMessageDto {
        return OfflineMessageDto(
            id = row[OfflineMessagesTable.id],
            senderId = row[OfflineMessagesTable.senderId],
            recipientId = row[OfflineMessagesTable.recipientId],
            senderName = row[OfflineMessagesTable.senderName],
            encryptedPayload = row[OfflineMessagesTable.encryptedPayload],
            timestamp = row[OfflineMessagesTable.timestamp].toEpochMilli(),
            delivered = row[OfflineMessagesTable.delivered],
            senderPublicKey = row[OfflineMessagesTable.senderPublicKey]
        )
    }
}
