package org.barter.features.authentication.dao.mapper

import org.barter.features.authentication.model.UserInfoDto
import org.jetbrains.exposed.sql.ResultRow

interface AuthenticationMapper {
    fun fromUserDaoToUserInfo(resultRow: ResultRow): UserInfoDto
}