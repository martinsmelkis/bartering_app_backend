package app.bartering.features.authentication.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.chat.db.OfflineMessagesTable
import app.bartering.features.chat.db.ReadReceiptsTable
import app.bartering.features.encryptedfiles.db.EncryptedFilesTable
import app.bartering.features.profile.db.UserRegistrationDataTable
import app.bartering.features.authentication.dao.mapper.AuthenticationMapper
import app.bartering.features.authentication.db.UserDeviceKeysTable
import app.bartering.features.authentication.model.DeviceKeyConstraints
import app.bartering.features.authentication.model.DeviceKeyInfo
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
import java.time.Instant
import java.util.UUID

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

    // ============================================================================
    // DEVICE KEY MANAGEMENT (Multi-Device Support)
    // ============================================================================

    override suspend fun getDeviceKey(userId: String, deviceId: String): DeviceKeyInfo? = dbQuery {
        UserDeviceKeysTable
            .selectAll()
            .where {
                (UserDeviceKeysTable.userId eq userId) and
                        (UserDeviceKeysTable.deviceId eq deviceId) and
                        (UserDeviceKeysTable.isActive eq true)
            }
            .firstOrNull()
            ?.let { rowToDeviceKeyInfo(it) }
    }

    override suspend fun getAllActiveDeviceKeys(userId: String): List<DeviceKeyInfo> = dbQuery {
        UserDeviceKeysTable
            .selectAll()
            .where {
                (UserDeviceKeysTable.userId eq userId) and
                        (UserDeviceKeysTable.isActive eq true)
            }
            .orderBy(UserDeviceKeysTable.lastUsedAt to SortOrder.DESC)
            .map { rowToDeviceKeyInfo(it) }
    }

    override suspend fun getAllDeviceKeys(userId: String): List<DeviceKeyInfo> = dbQuery {
        UserDeviceKeysTable
            .selectAll()
            .where { UserDeviceKeysTable.userId eq userId }
            .orderBy(UserDeviceKeysTable.createdAt to SortOrder.DESC)
            .map { rowToDeviceKeyInfo(it) }
    }

    override suspend fun registerDeviceKey(deviceKey: DeviceKeyInfo): Boolean = dbQuery {
        try {
            // Check device limits
            val activeCount = getActiveDeviceCount(deviceKey.userId)
            if (activeCount >= DeviceKeyConstraints.MAX_ACTIVE_DEVICES) {
                log.warn("User {} has reached the maximum number of active devices ({})",
                    deviceKey.userId, DeviceKeyConstraints.MAX_ACTIVE_DEVICES)
                return@dbQuery false
            }

            val totalCount = UserDeviceKeysTable
                .select(UserDeviceKeysTable.id)
                .where { UserDeviceKeysTable.userId eq deviceKey.userId }
                .count()

            if (totalCount >= DeviceKeyConstraints.MAX_DEVICES_PER_USER) {
                log.warn("User {} has reached the maximum total devices ({}). Cleanup required.",
                    deviceKey.userId, DeviceKeyConstraints.MAX_DEVICES_PER_USER)
                return@dbQuery false
            }

            // Validate device type and platform
            val validDeviceType = deviceKey.deviceType?.lowercase() in DeviceKeyConstraints.VALID_DEVICE_TYPES
            val validPlatform = deviceKey.platform?.lowercase() in DeviceKeyConstraints.VALID_PLATFORMS

            UserDeviceKeysTable.insert {
                it[id] = deviceKey.id
                it[userId] = deviceKey.userId
                it[deviceId] = deviceKey.deviceId
                it[publicKey] = deviceKey.publicKey
                it[deviceName] = deviceKey.deviceName
                it[deviceType] = if (validDeviceType) deviceKey.deviceType?.lowercase() else "other"
                it[platform] = if (validPlatform) deviceKey.platform?.lowercase() else "other"
                it[isActive] = true
                it[lastUsedAt] = Instant.now()
                it[createdAt] = Instant.parse(deviceKey.createdAt)
                it[deactivatedAt] = null
                it[deactivatedReason] = null
            }

            logDeviceActivityInternal(deviceKey.userId, deviceKey.deviceId, "register", null)

            log.info("Registered new device {} for user {}", deviceKey.deviceId, deviceKey.userId)
            true
        } catch (e: Exception) {
            log.error("Failed to register device key for user {}", deviceKey.userId, e)
            false
        }
    }

    override suspend fun updateDeviceLastUsed(userId: String, deviceId: String): Boolean = dbQuery {
        try {
            val updated = UserDeviceKeysTable.update({
                (UserDeviceKeysTable.userId eq userId) and
                        (UserDeviceKeysTable.deviceId eq deviceId)
            }) {
                it[lastUsedAt] = Instant.now()
            }
            updated > 0
        } catch (e: Exception) {
            log.error("Failed to update last used for device {} of user {}", deviceId, userId, e)
            false
        }
    }

    override suspend fun deactivateDeviceKey(
        userId: String,
        deviceId: String,
        reason: String
    ): Boolean = dbQuery {
        try {
            val validReason = reason.lowercase() in DeviceKeyConstraints.VALID_DEACTIVATION_REASONS

            val updated = UserDeviceKeysTable.update({
                (UserDeviceKeysTable.userId eq userId) and
                        (UserDeviceKeysTable.deviceId eq deviceId) and
                        (UserDeviceKeysTable.isActive eq true)
            }) {
                it[isActive] = false
                it[deactivatedAt] = Instant.now()
                it[deactivatedReason] = if (validReason) reason.lowercase() else "user_revoked"
            }

            if (updated > 0) {
                logDeviceActivityInternal(userId, deviceId, "revoke", null)
                log.info("Deactivated device {} for user {} (reason: {})", deviceId, userId, reason)
            }

            updated > 0
        } catch (e: Exception) {
            log.error("Failed to deactivate device {} for user {}", deviceId, userId, e)
            false
        }
    }

    override suspend fun reactivateDeviceKey(userId: String, deviceId: String): Boolean = dbQuery {
        try {
            // Check if we're at the limit
            val activeCount = getActiveDeviceCount(userId)
            if (activeCount >= DeviceKeyConstraints.MAX_ACTIVE_DEVICES) {
                log.warn("Cannot reactivate device {}: user {} already at max active devices",
                    deviceId, userId)
                return@dbQuery false
            }

            val updated = UserDeviceKeysTable.update({
                (UserDeviceKeysTable.userId eq userId) and
                        (UserDeviceKeysTable.deviceId eq deviceId) and
                        (UserDeviceKeysTable.isActive eq false)
            }) {
                it[isActive] = true
                it[deactivatedAt] = null
                it[deactivatedReason] = null
                it[lastUsedAt] = Instant.now()
            }

            if (updated > 0) {
                logDeviceActivityInternal(userId, deviceId, "reactivate", null)
                log.info("Reactivated device {} for user {}", deviceId, userId)
            }

            updated > 0
        } catch (e: Exception) {
            log.error("Failed to reactivate device {} for user {}", deviceId, userId, e)
            false
        }
    }

    override suspend fun updateDeviceInfo(
        userId: String,
        deviceId: String,
        deviceName: String?
    ): Boolean = dbQuery {
        try {
            val updated = UserDeviceKeysTable.update({
                (UserDeviceKeysTable.userId eq userId) and
                        (UserDeviceKeysTable.deviceId eq deviceId)
            }) {
                if (deviceName != null) {
                    it[UserDeviceKeysTable.deviceName] = deviceName
                }
            }
            updated > 0
        } catch (e: Exception) {
            log.error("Failed to update device info for {} of user {}", deviceId, userId, e)
            false
        }
    }

    override suspend fun getActiveDeviceCount(userId: String): Int = dbQuery {
        UserDeviceKeysTable
            .select(UserDeviceKeysTable.id)
            .where {
                (UserDeviceKeysTable.userId eq userId) and
                        (UserDeviceKeysTable.isActive eq true)
            }
            .count()
            .toInt()
    }

    override suspend fun isDeviceActive(userId: String, deviceId: String): Boolean = dbQuery {
        UserDeviceKeysTable
            .select(UserDeviceKeysTable.isActive)
            .where {
                (UserDeviceKeysTable.userId eq userId) and
                        (UserDeviceKeysTable.deviceId eq deviceId)
            }
            .firstOrNull()
            ?.get(UserDeviceKeysTable.isActive) ?: false
    }

    override suspend fun migrateDeviceKey(
        userId: String,
        sourceDeviceId: String,
        targetDeviceId: String,
        targetPublicKey: String,
        targetDeviceName: String?,
        targetDeviceType: String?,
        targetPlatform: String?
    ): Boolean = dbQuery {
        try {
            // 1. Verify source device exists and is active
            val sourceDevice = UserDeviceKeysTable
                .selectAll()
                .where {
                    (UserDeviceKeysTable.userId eq userId) and
                            (UserDeviceKeysTable.deviceId eq sourceDeviceId) and
                            (UserDeviceKeysTable.isActive eq true)
                }
                .firstOrNull()

            if (sourceDevice == null) {
                log.warn("Source device {} not found or inactive for user {}", sourceDeviceId, userId)
                return@dbQuery false
            }

            // 2. Check if target device already exists
            val existingTarget = UserDeviceKeysTable
                .selectAll()
                .where {
                    (UserDeviceKeysTable.userId eq userId) and
                            (UserDeviceKeysTable.deviceId eq targetDeviceId)
                }
                .firstOrNull()

            if (existingTarget != null) {
                log.warn("Target device {} already exists for user {}", targetDeviceId, userId)
                return@dbQuery false
            }

            // 3. Deactivate source device
            UserDeviceKeysTable.update({
                (UserDeviceKeysTable.userId eq userId) and
                        (UserDeviceKeysTable.deviceId eq sourceDeviceId)
            }) {
                it[isActive] = false
                it[deactivatedAt] = Instant.now()
                it[deactivatedReason] = "migration"
            }

            // 4. Register target device with new key
            val validDeviceType = targetDeviceType?.lowercase() in DeviceKeyConstraints.VALID_DEVICE_TYPES
            val validPlatform = targetPlatform?.lowercase() in DeviceKeyConstraints.VALID_PLATFORMS

            UserDeviceKeysTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[UserDeviceKeysTable.userId] = userId
                it[deviceId] = targetDeviceId
                it[publicKey] = targetPublicKey
                it[deviceName] = targetDeviceName
                it[deviceType] = if (validDeviceType) targetDeviceType?.lowercase() else "mobile"
                it[platform] = if (validPlatform) targetPlatform?.lowercase() else "other"
                it[isActive] = true
                it[lastUsedAt] = Instant.now()
                it[createdAt] = Instant.now()
                it[deactivatedAt] = null
                it[deactivatedReason] = null
            }

            logDeviceActivityInternal(userId, sourceDeviceId, "migrate_from", null)
            logDeviceActivityInternal(userId, targetDeviceId, "migrate_to", null)

            log.info("Migrated device key from {} to {} for user {}", sourceDeviceId, targetDeviceId, userId)
            true
        } catch (e: Exception) {
            log.error("Failed to migrate device key for user {}", userId, e)
            false
        }
    }

    override suspend fun logDeviceActivity(
        userId: String,
        deviceId: String,
        activityType: String,
        ipAddress: String?
    ) = dbQuery {
        logDeviceActivityInternal(userId, deviceId, activityType, ipAddress)
    }

    // ============================================================================
    // PRIVATE HELPERS
    // ============================================================================

    private fun rowToDeviceKeyInfo(row: ResultRow): DeviceKeyInfo {
        return DeviceKeyInfo(
            id = row[UserDeviceKeysTable.id],
            userId = row[UserDeviceKeysTable.userId],
            deviceId = row[UserDeviceKeysTable.deviceId],
            publicKey = row[UserDeviceKeysTable.publicKey],
            deviceName = row[UserDeviceKeysTable.deviceName],
            deviceType = row[UserDeviceKeysTable.deviceType],
            platform = row[UserDeviceKeysTable.platform],
            isActive = row[UserDeviceKeysTable.isActive],
            lastUsedAt = row[UserDeviceKeysTable.lastUsedAt].toString(),
            createdAt = row[UserDeviceKeysTable.createdAt].toString(),
            deactivatedAt = row[UserDeviceKeysTable.deactivatedAt]?.toString(),
            deactivatedReason = row[UserDeviceKeysTable.deactivatedReason]
        )
    }

    private fun logDeviceActivityInternal(
        userId: String,
        deviceId: String,
        activityType: String,
        ipAddress: String?
    ) {
        // Note: This should insert into user_device_activity table if you want full audit logging
        // For now, we just log to application logs
        log.debug("Device activity: user={}, device={}, type={}", userId, deviceId, activityType)
    }

    // ============================================================================
    // USER DELETION (Existing Implementation)
    // ============================================================================

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

                // Step 3: Delete device keys (explicit cleanup for multi-device support)
                val deviceKeysDeleted = UserDeviceKeysTable.deleteWhere {
                    UserDeviceKeysTable.userId eq userId
                }
                log.info("Deleted {} device keys for user {}", deviceKeysDeleted, userId)

                // Step 4: Delete migration sessions
                val migrationDao: app.bartering.features.migration.dao.MigrationSessionDao by inject(
                    app.bartering.features.migration.dao.MigrationSessionDao::class.java
                )
                val migrationSessionsDeleted = try {
                    migrationDao.deleteAllSessionsForUser(userId)
                } catch (e: Exception) {
                    log.warn("Failed to delete migration sessions for user {}", userId, e)
                    0
                }
                log.info("Deleted {} migration sessions for user {}", migrationSessionsDeleted, userId)

                // Step 5: Delete read receipts where user is sender or recipient
                // (These don't have FK constraints with CASCADE)
                val readReceiptsDeleted = ReadReceiptsTable.deleteWhere {
                    (ReadReceiptsTable.senderId eq userId) or
                            (ReadReceiptsTable.recipientId eq userId)
                }
                log.info("Deleted {} read receipts for user {}", readReceiptsDeleted, userId)

                // Step 6: Delete offline messages where user is sender or recipient
                // (These don't have FK constraints with CASCADE)
                val offlineMessagesDeleted = OfflineMessagesTable.deleteWhere {
                    (OfflineMessagesTable.senderId eq userId) or
                            (OfflineMessagesTable.recipientId eq userId)
                }
                log.info("Deleted {} offline messages for user {}", offlineMessagesDeleted, userId)

                // Step 7: Delete encrypted files where user is sender or recipient
                // (These don't have FK constraints with CASCADE)
                val encryptedFilesDeleted = EncryptedFilesTable.deleteWhere {
                    (EncryptedFilesTable.senderId eq userId) or
                            (EncryptedFilesTable.recipientId eq userId)
                }
                log.info("Deleted {} encrypted files for user {}", encryptedFilesDeleted, userId)

                // Step 8: Delete the user from user_registration_data
                // All related data will be automatically deleted due to ON DELETE CASCADE constraints:
                // - user_profiles, user_attributes, user_relationships, user_postings
                // - user_presence, user_notification_contacts, attribute_notification_preferences
                // - chat_response_times, user_reputation, user_reviews, barter_transactions
                // - federated_postings, user_device_keys (NEW)

                val deletedCount = UserRegistrationDataTable.deleteWhere {
                    UserRegistrationDataTable.id eq userId
                }

                if (deletedCount > 0) {
                    log.info("Successfully deleted user {} from database", userId)

                    // Step 7: Delete posting images asynchronously
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