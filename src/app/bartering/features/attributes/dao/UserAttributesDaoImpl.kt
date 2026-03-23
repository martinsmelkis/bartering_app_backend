package app.bartering.features.attributes.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.attributes.db.UserAttributesTable
import app.bartering.features.attributes.model.UserAttributeType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import java.math.BigDecimal

class UserAttributesDaoImpl : UserAttributesDao {

    override suspend fun create(
        userId: String,
        attributeId: String,
        type: UserAttributeType,
        relevancy: BigDecimal,
        description: String?
    ) {
        dbQuery {
            UserAttributesTable.insertIgnore {
                it[UserAttributesTable.userId] = userId
                it[UserAttributesTable.attributeId] = attributeId
                it[UserAttributesTable.type] = type
                it[UserAttributesTable.relevancy] = relevancy
                it[UserAttributesTable.description] = description
                // Other fields like 'condition', 'expiresAt' will use their defaults (null).
            }
        }
    }

    override suspend fun deleteUserAttributesByType(userId: String, type: UserAttributeType): Int {
        return dbQuery {
            UserAttributesTable.deleteWhere {
                (UserAttributesTable.userId eq userId) and (UserAttributesTable.type eq type)
            }
        }
    }
}
