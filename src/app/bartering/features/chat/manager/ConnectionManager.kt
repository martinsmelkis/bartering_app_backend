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
 * Supports multiple concurrent connections per user for different purposes:
 * - "general" connection for global chat messages, notifications, background P2P auth
 * - "direct-chat" connection for 1:1 direct messaging
 * - Other custom purposes as needed
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
    // Changed from single connection per user to multiple connections per user
    private val connections = ConcurrentHashMap<String, MutableSet<ChatConnection>>()
    private val connectionCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Register a new connection
     * Now supports multiple concurrent connections per user
     *
     * @param userId User ID
     * @param connection Chat connection instance
     * @param purpose Purpose/type of connection (e.g., "general", "direct-chat", "background")
     */
    suspend fun addConnection(userId: String, connection: ChatConnection, purpose: String = "general") {
        val userConnections = connections.getOrPut(userId) { ConcurrentHashMap.newKeySet() }

        connection.purpose = purpose
        userConnections.add(connection)

        val totalConnections = userConnections.size
        log.info("User {} added connection {} (purpose: {}). Total connections: {}",
            userId, connection.id, purpose, totalConnections)
    }

    /**
     * Get a single connection by user ID (backward compatibility)
     * Returns the most recent/active connection if multiple exist
     */
    fun getConnection(userId: String): ChatConnection? {
        val userConnections = connections[userId]
        return userConnections?.firstOrNull { it.session.isActive }
    }

    /**
     * Get all connections for a user
     */
    fun getAllConnections(userId: String): Set<ChatConnection> {
        return connections[userId]?.filter { it.session.isActive }?.toSet() ?: emptySet()
    }

    /**
     * Get a specific connection by user ID and connection ID
     */
    fun getConnection(userId: String, connectionId: Int): ChatConnection? {
        return connections[userId]?.firstOrNull { it.id == connectionId && it.session.isActive }
    }

    /**
     * Get the most recent/active general connection for a user
     * Preference order: general purpose connections > any active connection
     */
    fun getGeneralConnection(userId: String): ChatConnection? {
        val userConnections = connections[userId]
        return userConnections
            ?.firstOrNull { it.purpose == "general" && it.session.isActive }
            ?: userConnections?.firstOrNull { it.session.isActive }
    }

    /**
     * Remove a connection
     * Only removes if the connection ID matches to prevent race conditions
     */
    fun removeConnection(userId: String, connectionId: Int): Boolean {
        val userConnections = connections[userId] ?: return false

        val removed = userConnections.removeIf { it.id == connectionId }

        if (removed) {
            log.debug("Removed connection {} for user {}. Remaining connections: {}", connectionId, userId, userConnections.size)
            // Clean up empty connection sets
            if (userConnections.isEmpty()) {
                connections.remove(userId)
                log.info("User {} has no more active connections", userId)
            }
        } else {
            log.debug("Connection {} not found for user {}", connectionId, userId)
        }

        return removed
    }

    /**
     * Check if a user is currently connected
     */
    fun isConnected(userId: String): Boolean {
        return connections.containsKey(userId) && connections[userId]?.any { it.session.isActive } == true
    }

    /**
     * Get the count of active connections (users)
     */
    fun getActiveConnectionCount(): Int {
        return connections.size
    }

    /**
     * Get the total count of all connections (including multiple per user)
     */
    fun getTotalConnectionCount(): Int {
        return connections.values.sumOf { it.size }
    }

    /**
     * Get all connected user IDs
     */
    fun getConnectedUserIds(): Set<String> {
        return connections.keys.toSet()
    }

    /**
     * Broadcast a message to all active connections for a user
     * Returns the number of connections the message was sent to
     */
    suspend fun broadcastToAllConnections(userId: String, message: String): Int {
        val userConnections = connections[userId] ?: return 0
        var sentCount = 0

        for (connection in userConnections) {
            if (connection.session.isActive) {
                try {
                    connection.session.send(io.ktor.websocket.Frame.Text(message))
                    sentCount++
                } catch (e: Exception) {
                    log.error("Error broadcasting to connection {} for user {}",
                        connection.id, userId, e)
                }
            }
        }

        return sentCount
    }
}
