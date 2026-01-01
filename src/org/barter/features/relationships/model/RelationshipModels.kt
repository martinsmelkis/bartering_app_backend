package org.barter.features.relationships.model

import kotlinx.serialization.Serializable

/**
 * Request to create or update a relationship between users
 */
@Serializable
data class RelationshipRequest(
    val fromUserId: String,
    val toUserId: String,
    val relationshipType: String // Use string for JSON serialization
)

/**
 * Request to remove a relationship
 */
@Serializable
data class RemoveRelationshipRequest(
    val fromUserId: String,
    val toUserId: String,
    val relationshipType: String
)

/**
 * Response containing relationship information
 */
@Serializable
data class RelationshipResponse(
    val fromUserId: String,
    val toUserId: String,
    val relationshipType: String,
    val createdAt: String
)

/**
 * Response containing all relationships for a user
 */
@Serializable
data class UserRelationshipsResponse(
    val userId: String,
    val favorites: List<String> = emptyList(),
    val friends: List<String> = emptyList(),
    val friendRequestsSent: List<String> = emptyList(),
    val friendRequestsReceived: List<String> = emptyList(),
    val chattedWith: List<String> = emptyList(),
    val blocked: List<String> = emptyList(),
    val hidden: List<String> = emptyList(),
    val traded: List<String> = emptyList(),
    val tradeInterested: List<String> = emptyList()
)

/**
 * Detailed relationship information with user profile data
 */
@Serializable
data class RelationshipWithProfile(
    val userId: String,
    val userName: String?,
    val relationshipType: String,
    val createdAt: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isMutual: Boolean = false // For friend requests, trades, etc.
)

/**
 * Request to accept a friend request
 */
@Serializable
data class AcceptFriendRequestRequest(
    val userId: String,
    val friendUserId: String
)

/**
 * Statistics about user relationships
 */
@Serializable
data class RelationshipStats(
    val userId: String,
    val totalFriends: Int = 0,
    val totalTrades: Int = 0,
    val pendingFriendRequests: Int = 0
)
