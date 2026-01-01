package org.barter.features.authentication.dao

import org.barter.features.authentication.model.UserInfoDto

interface AuthenticationDao {
    suspend fun getUserInfoById(id: String): UserInfoDto?
    suspend fun deleteUserAndAllData(userId: String): Boolean
}