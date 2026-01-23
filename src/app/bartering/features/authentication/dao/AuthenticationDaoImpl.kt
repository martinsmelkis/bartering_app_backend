package app.bartering.features.authentication.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.chat.db.OfflineMessagesTable
import app.bartering.features.chat.db.ReadReceiptsTable
import app.bartering.features.encryptedfiles.db.EncryptedFilesTable
import app.bartering.features.profile.db.UserRegistrationDataTable
import app.bartering.features.authentication.dao.mapper.AuthenticationMapper
import app.bartering.features.authentication.model.UserInfoDto
import app.bartering.features.profile.cache.UserActivityCache
import app.bartering.features.postings.dao.UserPostingDao
import app.bartering.features.postings.service.ImageStorageService
import app.bartering.features.postings.service.LocalFileStorageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.or
import org.koin.java.KoinJavaComponent.inject
import org.slf4j.LoggerFactory

class AuthenticationDaoImpl(private val mapper: AuthenticationMapper) : AuthenticationDao {
    private val log = LoggerFactory.getLogger(this::class.java)

    override suspend fun getUserInfoById(id: String): UserInfoDto? {
        val userInfo = dbQuery {
            return@dbQuery try {
                val selectRow = UserRegistrationDataTable.selectAll().where(UserRegistrationDataTable.id eq id).first()
                mapper.fromUserDaoToUserInfo(selectRow)
            } catch (_: NoSuchElementException) {
                return@dbQuery null
            }
        }
        return userInfo
    }

    override suspend fun deleteUserAndAllData(userId: String): Boolean {
        return dbQuery {
            try {
                log.info("Starting deletion of user {} and all associated data", userId)
                
                // Step 1: Get all user's postings to delete associated images
                val postingDao: UserPostingDao by inject(UserPostingDao::class.java)
                val imageStorage: ImageStorageService = LocalFileStorageService()
                
                val userPostings = try {
                    postingDao.getUserPostings(userId, includeExpired = true)
                } catch (e: Exception) {
                    log.warn("Failed to get user postings for image cleanup", e)
                    emptyList()
                }
                
                log.info("Found {} postings for user {}", userPostings.size, userId)
                
                // Collect all image URLs from postings
                val allImageUrls = userPostings.flatMap { it.imageUrls }
                log.info("Found {} images to delete for user {}", allImageUrls.size, userId)
                
                // Step 2: Remove user from activity cache to prevent foreign key violations
                // during background sync operations
                UserActivityCache.removeUser(userId)
                
                // Step 3: Delete read receipts where user is sender or recipient
                // (These don't have FK constraints with CASCADE)
                val readReceiptsDeleted = ReadReceiptsTable.deleteWhere { 
                    (ReadReceiptsTable.senderId eq userId) or 
                    (ReadReceiptsTable.recipientId eq userId)
                }
                log.info("Deleted {} read receipts for user {}", readReceiptsDeleted, userId)
                
                // Step 4: Delete offline messages where user is sender or recipient
                // (These don't have FK constraints with CASCADE)
                val offlineMessagesDeleted = OfflineMessagesTable.deleteWhere { 
                    (OfflineMessagesTable.senderId eq userId) or 
                    (OfflineMessagesTable.recipientId eq userId)
                }
                log.info("Deleted {} offline messages for user {}", offlineMessagesDeleted, userId)
                
                // Step 5: Delete encrypted files where user is sender or recipient
                // (These don't have FK constraints with CASCADE)
                val encryptedFilesDeleted = EncryptedFilesTable.deleteWhere { 
                    (EncryptedFilesTable.senderId eq userId) or 
                    (EncryptedFilesTable.recipientId eq userId)
                }
                log.info("Deleted {} encrypted files for user {}", encryptedFilesDeleted, userId)
                
                // Step 6: Delete the user from user_registration_data
                // All related data will be automatically deleted due to ON DELETE CASCADE constraints:
                // - user_profiles (FK: user_id -> user_registration_data.id)
                // - user_attributes (FK: user_id -> user_registration_data.id)
                // - user_relationships (FK: user_id_from, user_id_to -> user_registration_data.id)
                // - user_postings (FK: user_id -> user_registration_data.id)
                //   - posting_attributes_link (FK: posting_id -> user_postings.id)
                //   - posting_notification_preferences (FK: posting_id -> user_postings.id)
                // - user_presence (FK: user_id -> user_registration_data.id)
                // - user_notification_contacts (FK: user_id -> user_registration_data.id)
                // - attribute_notification_preferences (FK: user_id -> user_registration_data.id)
                // - chat_response_times (FK: user_id -> user_registration_data.id)
                // - user_reputation (FK: user_id -> user_registration_data.id)
                // - user_reviews (FK: target_user_id -> user_registration_data.id)
                // - barter_transactions (FK: user1_id, user2_id -> user_registration_data.id)
                // - federated_postings (FK: local_user_id -> user_registration_data.id)
                
                val deletedCount = UserRegistrationDataTable.deleteWhere { 
                    UserRegistrationDataTable.id eq userId 
                }
                
                if (deletedCount > 0) {
                    log.info("Successfully deleted user {} from database", userId)
                    
                    // Step 7: Delete posting images asynchronously (don't block the database transaction)
                    if (allImageUrls.isNotEmpty()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                log.info("Starting async deletion of {} images for user {}", allImageUrls.size, userId)
                                val deletedImagesCount = imageStorage.deleteImages(allImageUrls)
                                log.info("Deleted {}/{} images for user {}", 
                                    deletedImagesCount, allImageUrls.size, userId)
                            } catch (e: Exception) {
                                log.error("Failed to delete images for user {}", userId, e)
                            }
                        }
                    }
                    
                    true
                } else {
                    log.warn("No user found with id {}", userId)
                    false
                }
            } catch (e: Exception) {
                log.error("Failed to delete user {} and associated data", userId, e)
                e.printStackTrace()
                false
            }
        }
    }

}