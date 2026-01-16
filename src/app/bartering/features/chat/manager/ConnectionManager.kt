package app.bartering.features.chat.manager

import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.isActive
import app.bartering.features.chat.model.ChatConnection
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(this::class.java)
    private val connections = ConcurrentHashMap<String, ChatConnection>()

    /**
     * Register a new connection
     * Closes any existing connection for the same user
     */
    suspend fun addConnection(userId: String, connection: ChatConnection) {
        // Close old session if exists
        connections[userId]?.let { oldConnection ->
            log.info("User {} reconnected, closing old session (ID: {})", userId, oldConnection.id)
            try {
                oldConnection.session.close(
                    CloseReason(
                        CloseReason.Codes.GOING_AWAY,
                        "New session started"
                    )
                )
            } catch (e: Exception) {
                log.error("Error closing old session for userId={}", userId, e)
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
            log.info("User {} (Connection ID: {}) removed from active connections", userId, connectionId)
            true
        } else {
            log.debug("User {} (Connection ID: {}) already had a newer session. Not removing", userId, connectionId)
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
