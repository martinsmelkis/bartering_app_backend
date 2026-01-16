package app.bartering.features.relationships.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.profile.db.UserProfilesTable
import app.bartering.features.relationships.db.UserRelationshipsTable
import app.bartering.features.relationships.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.time.format.DateTimeFormatter

class UserRelationshipsDaoImpl : UserRelationshipsDao {
    private val log = LoggerFactory.getLogger(this::class.java)

    override suspend fun createRelationship(
        fromUserId: String,
        toUserId: String,
        relationshipType: RelationshipType
    ): Boolean = dbQuery {
        try {
            UserRelationshipsTable.insertIgnore {
                it[userIdFrom] = fromUserId
                it[userIdTo] = toUserId
                it[UserRelationshipsTable.relationshipType] = relationshipType.value
            }.insertedCount > 0
        } catch (e: Exception) {
            log.error("Error creating relationship from {} to {}", fromUserId, toUserId, e)
            false
        }
    }

    override suspend fun removeRelationship(
        fromUserId: String,
        toUserId: String,
        relationshipType: RelationshipType
    ): Boolean = dbQuery {
        try {
            UserRelationshipsTable.deleteWhere {
                (userIdFrom eq fromUserId) and
                        (userIdTo eq toUserId) and
                        (UserRelationshipsTable.relationshipType eq relationshipType.value)
            } > 0
        } catch (e: Exception) {
            log.error("Error removing relationship from {} to {}", fromUserId, toUserId, e)
            false
        }
    }

    override suspend fun getUserRelationships(userId: String): UserRelationshipsResponse = dbQuery {
        // Get all outgoing relationships
        val outgoingRelationships = UserRelationshipsTable
            .selectAll()
            .where { UserRelationshipsTable.userIdFrom eq userId }
            .groupBy { it[UserRelationshipsTable.relationshipType] }

        // Get all incoming friend requests
        val incomingFriendRequests = UserRelationshipsTable
            .selectAll()
            .where {
                (UserRelationshipsTable.userIdTo eq userId) and
                        (UserRelationshipsTable.relationshipType eq RelationshipType.FRIEND_REQUEST_SENT.value)
            }
            .map { it[UserRelationshipsTable.userIdFrom] }

        UserRelationshipsResponse(
            userId = userId,
            favorites = outgoingRelationships[RelationshipType.FAVORITE.value]
                ?.map { it[UserRelationshipsTable.userIdTo] } ?: emptyList(),
            friends = outgoingRelationships[RelationshipType.FRIEND.value]
                ?.map { it[UserRelationshipsTable.userIdTo] } ?: emptyList(),
            friendRequestsSent = outgoingRelationships[RelationshipType.FRIEND_REQUEST_SENT.value]
                ?.map { it[UserRelationshipsTable.userIdTo] } ?: emptyList(),
            friendRequestsReceived = incomingFriendRequests,
            chattedWith = outgoingRelationships[RelationshipType.CHATTED.value]
                ?.map { it[UserRelationshipsTable.userIdTo] } ?: emptyList(),
            blocked = outgoingRelationships[RelationshipType.BLOCKED.value]
                ?.map { it[UserRelationshipsTable.userIdTo] } ?: emptyList(),
            hidden = outgoingRelationships[RelationshipType.HIDDEN.value]
                ?.map { it[UserRelationshipsTable.userIdTo] } ?: emptyList(),
            traded = outgoingRelationships[RelationshipType.TRADED.value]
                ?.map { it[UserRelationshipsTable.userIdTo] } ?: emptyList(),
            tradeInterested = outgoingRelationships[RelationshipType.TRADE_INTERESTED.value]
                ?.map { it[UserRelationshipsTable.userIdTo] } ?: emptyList()
        )
    }

    override suspend fun getRelationshipsByType(
        userId: String,
        relationshipType: RelationshipType
    ): List<String> = dbQuery {
        log.debug("Getting relationships by type for userId={}, type={}", userId, relationshipType)
        UserRelationshipsTable
            .selectAll()
            .where {
                (UserRelationshipsTable.userIdFrom eq userId) and
                        (UserRelationshipsTable.relationshipType eq relationshipType.value)
            }
            .map { it[UserRelationshipsTable.userIdTo] }
    }

    override suspend fun relationshipExists(
        fromUserId: String,
        toUserId: String,
        relationshipType: RelationshipType
    ): Boolean = dbQuery {
        UserRelationshipsTable
            .selectAll()
            .where {
                (UserRelationshipsTable.userIdFrom eq fromUserId) and
                        (UserRelationshipsTable.userIdTo eq toUserId) and
                        (UserRelationshipsTable.relationshipType eq relationshipType.value)
            }
            .count() > 0
    }

    override suspend fun getRelationshipsWithProfiles(
        userId: String,
        relationshipType: RelationshipType
    ): List<RelationshipWithProfile> = dbQuery {
        val join = UserRelationshipsTable
            .join(
                UserProfilesTable,
                JoinType.LEFT,
                UserRelationshipsTable.userIdTo,
                UserProfilesTable.userId
            )

        join
            .selectAll()
            .where {
                (UserRelationshipsTable.userIdFrom eq userId) and
                        (UserRelationshipsTable.relationshipType eq relationshipType.value)
            }
            .map { row ->
                // Check if relationship is mutual (both users have same relationship type)
                val isMutual = UserRelationshipsTable
                    .selectAll()
                    .where {
                        (UserRelationshipsTable.userIdFrom eq row[UserRelationshipsTable.userIdTo]) and
                                (UserRelationshipsTable.userIdTo eq userId) and
                                (UserRelationshipsTable.relationshipType eq relationshipType.value)
                    }
                    .count() > 0

                RelationshipWithProfile(
                    userId = row[UserRelationshipsTable.userIdTo],
                    userName = row.getOrNull(UserProfilesTable.name),
                    relationshipType = row[UserRelationshipsTable.relationshipType],
                    createdAt = DateTimeFormatter.ISO_INSTANT.format(row[UserRelationshipsTable.createdAt]),
                    latitude = row.getOrNull(UserProfilesTable.location)?.firstPoint?.y,
                    longitude = row.getOrNull(UserProfilesTable.location)?.firstPoint?.x,
                    isMutual = isMutual
                )
            }
    }

    override suspend fun acceptFriendRequest(userId: String, friendUserId: String): Boolean =
        dbQuery {
            try {
                // Check if friend request exists
                val requestExists = UserRelationshipsTable
                    .selectAll()
                    .where {
                        (UserRelationshipsTable.userIdFrom eq friendUserId) and
                                (UserRelationshipsTable.userIdTo eq userId) and
                                (UserRelationshipsTable.relationshipType eq RelationshipType.FRIEND_REQUEST_SENT.value)
                    }
                    .count() > 0

                if (!requestExists) {
                    log.warn("Friend request doesn't exist for fromUserId={}, toUserId={}", userId, friendUserId)
                    return@dbQuery false
                }

                // Remove the friend request
                UserRelationshipsTable.deleteWhere {
                    (userIdFrom eq friendUserId) and
                            (userIdTo eq userId) and
                            (relationshipType eq RelationshipType.FRIEND_REQUEST_SENT.value)
                }

                // Create mutual FRIEND relationships
                UserRelationshipsTable.insertIgnore {
                    it[userIdFrom] = userId
                    it[userIdTo] = friendUserId
                    it[relationshipType] = RelationshipType.FRIEND.value
                }

                UserRelationshipsTable.insertIgnore {
                    it[userIdFrom] = friendUserId
                    it[userIdTo] = userId
                    it[relationshipType] = RelationshipType.FRIEND.value
                }

                true
            } catch (e: Exception) {
                log.error("Error accepting friend request from {} to {}", userId, friendUserId, e)
                false
            }
        }

    override suspend fun rejectFriendRequest(userId: String, friendUserId: String): Boolean =
        dbQuery {
            try {
                UserRelationshipsTable.deleteWhere {
                    (userIdFrom eq friendUserId) and
                            (userIdTo eq userId) and
                            (relationshipType eq RelationshipType.FRIEND_REQUEST_SENT.value)
                } > 0
            } catch (e: Exception) {
                log.error("Error rejecting friend request from {} to {}", userId, friendUserId, e)
                false
            }
        }

    override suspend fun getRelationshipStats(userId: String): RelationshipStats = dbQuery {
        val friends = UserRelationshipsTable
            .selectAll()
            .where {
                (UserRelationshipsTable.userIdFrom eq userId) and
                        (UserRelationshipsTable.relationshipType eq RelationshipType.FRIEND.value)
            }
            .count()

        val trades = UserRelationshipsTable
            .selectAll()
            .where {
                (UserRelationshipsTable.userIdFrom eq userId) and
                        (UserRelationshipsTable.relationshipType eq RelationshipType.TRADED.value)
            }
            .count()

        val pendingRequests = UserRelationshipsTable
            .selectAll()
            .where {
                (UserRelationshipsTable.userIdTo eq userId) and
                        (UserRelationshipsTable.relationshipType eq RelationshipType.FRIEND_REQUEST_SENT.value)
            }
            .count()

        RelationshipStats(
            userId = userId,
            totalFriends = friends.toInt(),
            totalTrades = trades.toInt(),
            pendingFriendRequests = pendingRequests.toInt()
        )
    }

    override suspend fun isBlocked(fromUserId: String, toUserId: String): Boolean = dbQuery {
        UserRelationshipsTable
            .selectAll()
            .where {
                (UserRelationshipsTable.userIdFrom eq fromUserId) and
                        (UserRelationshipsTable.userIdTo eq toUserId) and
                        (UserRelationshipsTable.relationshipType eq RelationshipType.BLOCKED.value)
            }
            .count() > 0
    }

    override suspend fun getBlockedByUsers(userId: String): List<String> = dbQuery {
        UserRelationshipsTable
            .selectAll()
            .where {
                (UserRelationshipsTable.userIdTo eq userId) and
                        (UserRelationshipsTable.relationshipType eq RelationshipType.BLOCKED.value)
            }
            .map { it[UserRelationshipsTable.userIdFrom] }
    }
}
