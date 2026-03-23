package app.bartering.features.authentication.mapper

import app.bartering.features.authentication.dao.mapper.AuthenticationMapper
import app.bartering.features.profile.db.UserRegistrationDataTable
import app.bartering.features.authentication.model.UserInfoDto
import org.jetbrains.exposed.v1.core.ResultRow

class AuthenticationMapperImpl : AuthenticationMapper {

    override fun fromUserDaoToUserInfo(resultRow: ResultRow) = UserInfoDto(
        id = resultRow[UserRegistrationDataTable.id],
        publicKey = resultRow[UserRegistrationDataTable.publicKey]
    )

}