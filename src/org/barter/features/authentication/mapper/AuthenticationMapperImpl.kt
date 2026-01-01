package org.barter.features.authentication.dao.mapper

import org.barter.features.profile.db.UserRegistrationDataTable
import org.barter.features.authentication.model.UserInfoDto
import org.jetbrains.exposed.sql.ResultRow

class AuthenticationMapperImpl : AuthenticationMapper {

    override fun fromUserDaoToUserInfo(resultRow: ResultRow) = UserInfoDto(
        id = resultRow[UserRegistrationDataTable.id],
        publicKey = resultRow[UserRegistrationDataTable.publicKey]
    )

}