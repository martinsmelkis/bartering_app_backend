package app.bartering.features.chat.model

import io.ktor.server.websocket.DefaultWebSocketServerSession
import java.util.concurrent.atomic.AtomicInteger

class ChatConnection(val session: DefaultWebSocketServerSession) {
    companion object {
        val lastId = AtomicInteger(0) // For unique connection IDs (optional)
    }
    val id = lastId.incrementAndGet() // Unique ID for this connection
    var userId: String? = null // Will be set after successful authentication
    var userName: String? = null
    var userPublicKey: String? = null
    var recipientPublicKey: String? = null
}