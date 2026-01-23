package app.bartering.features.chat.dao

import app.bartering.features.chat.db.ReadReceiptsTable
import app.bartering.features.chat.model.MessageStatus
import app.bartering.features.chat.model.ReadReceiptDto
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * DAO for managing message read receipts
 */
interface ReadReceiptDao {
    /**
     * Store or update a read receipt
     * @param receipt Read receipt data
     * @return true if successful
     */
    suspend fun storeReadReceipt(receipt: ReadReceiptDto): Boolean
    
    /**
     * Get read receipts for specific messages
     * @param messageIds List of message IDs
     * @return List of read receipts
     */
    suspend fun getReadReceipts(messageIds: List<String>): List<ReadReceiptDto>
    
    /**
     * Get read receipts for a sender (to show status of their sent messages)
     * @param senderId Sender user ID
     * @param limit Maximum number of receipts to return
     * @return List of read receipts
     */
    suspend fun getReadReceiptsForSender(senderId: String, limit: Int = 100): List<ReadReceiptDto>
    
    /**
     * Get the latest status of a specific message
     * @param messageId Message ID
     * @return Read receipt or null if not found
     */
    suspend fun getMessageStatus(messageId: String): ReadReceiptDto?
    
    /**
     * Delete old read receipts (cleanup)
     * @param olderThan Delete receipts older than this instant
     * @return Number of deleted receipts
     */
    suspend fun deleteOldReceipts(olderThan: java.time.Instant): Int
}

/**
 * Implementation of ReadReceiptDao using Exposed
 */
class ReadReceiptDaoImpl : ReadReceiptDao {
    private val log = LoggerFactory.getLogger(this::class.java)
    
    override suspend fun storeReadReceipt(receipt: ReadReceiptDto): Boolean {
        return try {
            transaction {
                ReadReceiptsTable.insertIgnore {
                    it[messageId] = receipt.messageId
                    it[senderId] = receipt.senderId
                    it[recipientId] = receipt.recipientId
                    it[status] = receipt.status.name
                    it[timestamp] = receipt.timestamp
                    it[createdAt] = receipt.createdAt
                }
                
                // If already exists, update the status if it's progressing forward
                ReadReceiptsTable.update({ 
                    (ReadReceiptsTable.messageId eq receipt.messageId) and 
                    (ReadReceiptsTable.recipientId eq receipt.recipientId)
                }) {
                    // Only update if new status is "higher" (SENT < DELIVERED < READ)
                    it[status] = receipt.status.name
                    it[timestamp] = receipt.timestamp
                }
            }
            true
        } catch (e: Exception) {
            log.error("Failed to store read receipt for messageId={}", receipt.messageId, e)
            false
        }
    }
    
    override suspend fun getReadReceipts(messageIds: List<String>): List<ReadReceiptDto> {
        if (messageIds.isEmpty()) return emptyList()
        
        return try {
            transaction {
                ReadReceiptsTable.select(ReadReceiptsTable.messageId inList messageIds)
                .map { row ->
                    ReadReceiptDto(
                        messageId = row[ReadReceiptsTable.messageId],
                        senderId = row[ReadReceiptsTable.senderId],
                        recipientId = row[ReadReceiptsTable.recipientId],
                        status = MessageStatus.valueOf(row[ReadReceiptsTable.status]),
                        timestamp = row[ReadReceiptsTable.timestamp],
                        createdAt = row[ReadReceiptsTable.createdAt]
                    )
                }
            }
        } catch (e: Exception) {
            log.error("Failed to get read receipts for messages", e)
            emptyList()
        }
    }
    
    override suspend fun getReadReceiptsForSender(senderId: String, limit: Int): List<ReadReceiptDto> {
        return try {
            transaction {
                ReadReceiptsTable.select(ReadReceiptsTable.senderId eq senderId)
                 .orderBy(ReadReceiptsTable.createdAt, SortOrder.DESC)
                 .limit(limit)
                 .map { row ->
                    ReadReceiptDto(
                        messageId = row[ReadReceiptsTable.messageId],
                        senderId = row[ReadReceiptsTable.senderId],
                        recipientId = row[ReadReceiptsTable.recipientId],
                        status = MessageStatus.valueOf(row[ReadReceiptsTable.status]),
                        timestamp = row[ReadReceiptsTable.timestamp],
                        createdAt = row[ReadReceiptsTable.createdAt]
                    )
                }
            }
        } catch (e: Exception) {
            log.error("Failed to get read receipts for senderId={}", senderId, e)
            emptyList()
        }
    }
    
    override suspend fun getMessageStatus(messageId: String): ReadReceiptDto? {
        return try {
            transaction {
                ReadReceiptsTable.select(ReadReceiptsTable.messageId eq messageId)
                 .firstOrNull()?.let { row ->
                    ReadReceiptDto(
                        messageId = row[ReadReceiptsTable.messageId],
                        senderId = row[ReadReceiptsTable.senderId],
                        recipientId = row[ReadReceiptsTable.recipientId],
                        status = MessageStatus.valueOf(row[ReadReceiptsTable.status]),
                        timestamp = row[ReadReceiptsTable.timestamp],
                        createdAt = row[ReadReceiptsTable.createdAt]
                    )
                }
            }
        } catch (e: Exception) {
            log.error("Failed to get message status for messageId={}", messageId, e)
            null
        }
    }
    
    override suspend fun deleteOldReceipts(olderThan: java.time.Instant): Int {
        return try {
            transaction {
                ReadReceiptsTable.deleteWhere {
                    createdAt less olderThan
                }
            }
        } catch (e: Exception) {
            log.error("Failed to delete old read receipts", e)
            0
        }
    }
}
