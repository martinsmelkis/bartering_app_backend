package org.barter.features.encryptedfiles.dao

import org.barter.extensions.DatabaseFactory.dbQuery
import org.barter.features.encryptedfiles.db.EncryptedFilesTable
import org.barter.features.encryptedfiles.model.EncryptedFileDto
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import java.time.Instant

/**
 * Implementation of EncryptedFileDao for database operations
 */
class EncryptedFileDaoImpl : EncryptedFileDao {

    override suspend fun storeEncryptedFile(
        fileDto: EncryptedFileDto,
        encryptedData: ByteArray
    ): Boolean = dbQuery {
        try {
            EncryptedFilesTable.insert {
                it[id] = fileDto.id
                it[senderId] = fileDto.senderId
                it[recipientId] = fileDto.recipientId
                it[filename] = fileDto.filename
                it[mimeType] = fileDto.mimeType
                it[fileSize] = fileDto.fileSize
                it[EncryptedFilesTable.encryptedData] = ExposedBlob(encryptedData)
                it[expiresAt] = Instant.ofEpochMilli(fileDto.expiresAt)
                it[downloaded] = false
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun getFileMetadata(fileId: String): EncryptedFileDto? = dbQuery {
        EncryptedFilesTable
            .selectAll()
            .where { EncryptedFilesTable.id eq fileId }
            .singleOrNull()
            ?.let { rowToFileDto(it) }
    }

    override suspend fun getEncryptedFileContent(fileId: String): ByteArray? = dbQuery {
        EncryptedFilesTable
            .select(EncryptedFilesTable.encryptedData)
            .where { EncryptedFilesTable.id eq fileId }
            .singleOrNull()
            ?.get(EncryptedFilesTable.encryptedData)
            ?.bytes
    }

    override suspend fun getEncryptedFile(fileId: String): Pair<EncryptedFileDto, ByteArray>? =
        dbQuery {
            EncryptedFilesTable
                .selectAll()
                .where { EncryptedFilesTable.id eq fileId }
                .singleOrNull()
                ?.let { row ->
                    val metadata = rowToFileDto(row)
                    val content = row[EncryptedFilesTable.encryptedData].bytes
                    Pair(metadata, content)
                }
        }

    override suspend fun markAsDownloaded(fileId: String): Boolean = dbQuery {
        try {
            EncryptedFilesTable.update({ EncryptedFilesTable.id eq fileId }) {
                it[downloaded] = true
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun deleteExpiredFiles(): Int = dbQuery {
        val now = Instant.now()
        // Delete files that are expired OR already downloaded
        EncryptedFilesTable.deleteWhere {
            (EncryptedFilesTable.expiresAt less now) or (EncryptedFilesTable.downloaded eq true)
        }
    }

    override suspend fun deleteFile(fileId: String): Boolean = dbQuery {
        try {
            EncryptedFilesTable.deleteWhere { EncryptedFilesTable.id eq fileId } > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun getPendingFiles(userId: String): List<EncryptedFileDto> = dbQuery {
        val now = Instant.now()
        EncryptedFilesTable
            .selectAll()
            .where {
                (EncryptedFilesTable.recipientId eq userId) and
                        (EncryptedFilesTable.downloaded eq false) and
                        (EncryptedFilesTable.expiresAt greater now)
            }
            .orderBy(EncryptedFilesTable.createdAt)
            .map { rowToFileDto(it) }
    }

    private fun rowToFileDto(row: ResultRow): EncryptedFileDto {
        return EncryptedFileDto(
            id = row[EncryptedFilesTable.id],
            senderId = row[EncryptedFilesTable.senderId],
            recipientId = row[EncryptedFilesTable.recipientId],
            filename = row[EncryptedFilesTable.filename],
            mimeType = row[EncryptedFilesTable.mimeType],
            fileSize = row[EncryptedFilesTable.fileSize],
            expiresAt = row[EncryptedFilesTable.expiresAt].toEpochMilli(),
            downloaded = row[EncryptedFilesTable.downloaded]
        )
    }
}
