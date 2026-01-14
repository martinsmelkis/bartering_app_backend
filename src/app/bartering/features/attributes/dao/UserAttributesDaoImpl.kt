package app.bartering.features.attributes.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.attributes.db.UserAttributesTable
import app.bartering.features.attributes.model.UserAttributeType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
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
