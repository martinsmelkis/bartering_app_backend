package app.bartering.features.authentication.dao.mapper

import app.bartering.features.authentication.model.UserInfoDto
import org.jetbrains.exposed.sql.ResultRow

interface AuthenticationMapper {
    fun fromUserDaoToUserInfo(resultRow: ResultRow): UserInfoDto
}