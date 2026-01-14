package app.bartering.features.authentication.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.chat.db.OfflineMessagesTable
import app.bartering.features.encryptedfiles.db.EncryptedFilesTable
import app.bartering.features.profile.db.UserRegistrationDataTable
import app.bartering.features.authentication.dao.mapper.AuthenticationMapper
import app.bartering.features.authentication.model.UserInfoDto
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.or

class AuthenticationDaoImpl(private val mapper: AuthenticationMapper) : AuthenticationDao {

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
                // Step 1: Delete offline messages where user is sender or recipient
                // (These don't have FK constraints with CASCADE)
                OfflineMessagesTable.deleteWhere { 
                    (OfflineMessagesTable.senderId eq userId) or 
                    (OfflineMessagesTable.recipientId eq userId)
                }
                
                // Step 2: Delete encrypted files where user is sender or recipient
                // (These don't have FK constraints with CASCADE)
                EncryptedFilesTable.deleteWhere { 
                    (EncryptedFilesTable.senderId eq userId) or 
                    (EncryptedFilesTable.recipientId eq userId)
                }
                
                // Step 3: Delete the user from user_registration_data
                // All related data will be automatically deleted due to ON DELETE CASCADE constraints:
                // - user_profiles (FK: user_id -> user_registration_data.id)
                // - user_attributes (FK: user_id -> user_registration_data.id)
                // - user_relationships (FK: user_id_from, user_id_to -> user_registration_data.id)
                // - user_postings (FK: user_id -> user_registration_data.id)
                //   - posting_attributes_link (FK: posting_id -> user_postings.id)
                
                val deletedCount = UserRegistrationDataTable.deleteWhere { 
                    UserRegistrationDataTable.id eq userId 
                }
                
                // Return true if at least one row was deleted
                deletedCount > 0
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

}