package app.bartering.features.authentication.dao

import app.bartering.features.authentication.model.UserInfoDto

interface AuthenticationDao {
    suspend fun getUserInfoById(id: String): UserInfoDto?
    suspend fun deleteUserAndAllData(userId: String): Boolean
}