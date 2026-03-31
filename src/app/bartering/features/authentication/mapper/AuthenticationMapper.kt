package app.bartering.features.authentication.mapper

import app.bartering.features.authentication.model.UserInfoDto
import org.jetbrains.exposed.v1.core.ResultRow

interface AuthenticationMapper {
    fun fromUserDaoToUserInfo(resultRow: ResultRow): UserInfoDto
}