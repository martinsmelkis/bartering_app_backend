package org.barter.features.chat.manager

import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.isActive
import org.barter.features.chat.model.ChatConnection
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages WebSocket connections for chat functionality
 *
 * In a production environment with multiple server instances, this should be replaced
 * with a distributed solution like Redis to enable:
 * - Cross-server message routing
 * - Connection state synchronization
 * - Horizontal scaling
 * - Session persistence across server restarts
 *
 * For Redis implementation, consider using:
 * - Redis Pub/Sub for real-time message routing between servers
 * - Redis Hash for storing connection metadata
 * - Redis Sets for tracking online users
 */
class ConnectionManager {
    private val connections = ConcurrentHashMap<String, ChatConnection>()

    /**
     * Register a new connection
     * Closes any existing connection for the same user
     */
    suspend fun addConnection(userId: String, connection: ChatConnection) {
        // Close old session if exists
        connections[userId]?.let { oldConnection ->
            println("User $userId reconnected, closing old session (ID: ${oldConnection.id})")
            try {
                oldConnection.session.close(
                    CloseReason(
                        CloseReason.Codes.GOING_AWAY,
                        "New session started"
                    )
                )
            } catch (e: Exception) {
                println("Error closing old session for user $userId: ${e.message}")
            }
        }
        connections[userId] = connection
    }

    /**
     * Get a connection by user ID
     */
    fun getConnection(userId: String): ChatConnection? {
        return connections[userId]
    }

    /**
     * Remove a connection
     * Only removes if the connection ID matches to prevent removing newer sessions
     */
    fun removeConnection(userId: String, connectionId: Int): Boolean {
        val currentConnection = connections[userId]
        return if (currentConnection?.id == connectionId) {
            connections.remove(userId)
            println("User $userId (Connection ID: $connectionId) removed from active connections.")
            true
        } else {
            println("User $userId (Connection ID: $connectionId) already had a newer session. Not removing.")
            false
        }
    }

    /**
     * Check if a user is currently connected
     */
    fun isConnected(userId: String): Boolean {
        return connections.containsKey(userId) && connections[userId]?.session?.isActive == true
    }

    /**
     * Get the count of active connections
     */
    fun getActiveConnectionCount(): Int {
        return connections.size
    }

    /**
     * Get all connected user IDs
     */
    fun getConnectedUserIds(): Set<String> {
        return connections.keys.toSet()
    }
}
