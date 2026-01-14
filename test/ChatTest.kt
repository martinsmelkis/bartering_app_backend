package app.bartering // Or your actual package

import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import app.bartering.module // Your main application module function
import app.bartering.features.chat.model.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ChatRoutesTest {

    // Consistent Json configuration for tests
    private val testJson = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        classDiscriminator = "type" // Assuming you use a class discriminator for SocketMessage
    }

    @Test
    fun `test successful authentication`() = testApplication {
        application { module(testing = true) } // Reference your main module
        val client = createClient {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(testJson)
            }
        }

        client.webSocket("/chat") {
            val userId = "userTestAuth_${System.currentTimeMillis()}"
            val authRequest = AuthRequest(userId = userId, token = "valid_token", peerUserId = "", publicKey = "test_pk")
            sendSerialized(authRequest)

            val responseFrame = incoming.receive() as? Frame.Text
            assertNotNull(responseFrame, "Did not receive a response frame.")
            val authResponse = testJson.decodeFromString<SocketMessage>(responseFrame.readText()) as? AuthResponse

            assertNotNull(authResponse, "Response was not an AuthResponse.")
            assertTrue(authResponse.success, "Authentication should succeed. Message: ${authResponse.message}")
            assertEquals("Authenticated as $userId", authResponse.message)
        }
    }

    @Test
    fun `test authentication failure for invalid token or userId`() = testApplication {
        application { module(testing = true) }
        val client = createClient {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(testJson)
            }
        }

        client.webSocket("/chat") {
            val authRequest = AuthRequest(userId = "", token = "invalid_token", peerUserId = "", publicKey = "") // Empty userId
            sendSerialized(authRequest)

            val responseFrame = incoming.receive() as? Frame.Text
            assertNotNull(responseFrame, "Did not receive a response frame for failed auth.")
            val authResponse = testJson.decodeFromString<SocketMessage>(responseFrame.readText()) as? AuthResponse

            assertNotNull(authResponse, "Response was not an AuthResponse for failed auth.")
            assertFalse(authResponse.success, "Authentication should fail.")
            assertTrue(authResponse.message.contains("Invalid userId or token"), "Unexpected error message: ${authResponse.message}")

            // Server should close the connection after failed auth.
            // Try to receive another frame; it should be a close frame or the channel should be closed.
            val nextFrame = withTimeoutOrNull(500) { incoming.receiveCatching().getOrNull() }
            assertTrue(nextFrame is Frame.Close || nextFrame == null, "Connection should be closed after failed auth.")
        }
    }

    @Test
    fun `test message relay between two users`() = testApplication {
        application { module(testing = true) }
        val client = createClient {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(testJson)
            }
        }

        val user1Id = "user1_${System.currentTimeMillis()}"
        val user2Id = "user2_${System.currentTimeMillis()}"

        var user1ReceivedServerMessage: ServerChatMessage? = null
        var user2ReceivedServerMessage: ServerChatMessage? = null

        val user1Job = this.application.launch {
            client.webSocket("/chat") { // User 1
                // 1. Authenticate User 1
                sendSerialized(AuthRequest(userId = user1Id, token = "token1", peerUserId = user2Id, publicKey = "pk1"))
                val auth1Frame = incoming.receive() as Frame.Text
                val auth1Response = testJson.decodeFromString<SocketMessage>(auth1Frame.readText()) as AuthResponse
                assertTrue(auth1Response.success, "User 1 Auth failed: ${auth1Response.message}")

                // User 1: Listen for messages
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        val msg = testJson.decodeFromString<SocketMessage>(frame.readText())
                        if (msg is ServerChatMessage) {
                            user1ReceivedServerMessage = msg
                            println("User 1 received: $user1ReceivedServerMessage")
                        }
                    }
                }
            }
        }

        val user2Job = this.application.launch {
            client.webSocket("/chat") { // User 2
                // 1. Authenticate User 2
                sendSerialized(AuthRequest(userId = user2Id, token = "token2", peerUserId = user1Id, publicKey = "pk2"))
                val auth2Frame = incoming.receive() as Frame.Text
                val auth2Response = testJson.decodeFromString<SocketMessage>(auth2Frame.readText()) as AuthResponse
                assertTrue(auth2Response.success, "User 2 Auth failed: ${auth2Response.message}")

                // User 2: Listen for messages
                val listenJob = launch {
                    incoming.consumeEach { frame ->
                        if (frame is Frame.Text) {
                            val msg = testJson.decodeFromString<SocketMessage>(frame.readText())
                            if (msg is ServerChatMessage) {
                                user2ReceivedServerMessage = msg
                                println("User 2 received: $user2ReceivedServerMessage")
                            }
                        }
                    }
                }

                // 2. User 2 sends a message to User 1
                val messageToUser1Text = "Hello User 1 from User 2!"
                // Assuming ClientChatMessageData takes plain text for testing simplicity,
                // or a placeholder for encryptedPayload if your server expects it.
                val clientMessageToUser1 = ClientChatMessage(
                    type = "",
                    data = ChatMessageData(
                        id = "1",
                        senderId = "2",
                        timestamp = System.currentTimeMillis().toString(),
                    recipientId = user1Id,
                    encryptedPayload = messageToUser1Text, // Placeholder or actual encrypted
                    )
                )
                sendSerialized(clientMessageToUser1)
                println("User 2 sent message to User 1")

                // Wait a bit for User 1 to receive
                kotlinx.coroutines.delay(500) // Adjust as needed

                // 3. (Optional) User 1 sends a message to User 2
                // This would require User 1's client to send a message.
                // For this test, we'll focus on one-way for simplicity of assertion.

                listenJob.cancel() // Stop listening after test actions
            }
        }

        // Wait for jobs to complete or a timeout
        withTimeoutOrNull(5000) { // Timeout for the test
            user1Job.join()
            user2Job.join()
        }

        // Assertions
        assertNotNull(user1ReceivedServerMessage, "User 1 should have received a message from User 2.")
        assertEquals(user2Id, user1ReceivedServerMessage?.senderId, "Message sender ID should be User 2.")
        // If you are sending plain text in ClientChatMessage for testing, or if ServerChatMessage has a plain text field
        assertEquals("Hello User 1 from User 2!", user1ReceivedServerMessage?.text, "Message content mismatch for User 1.")
        // Add assertions for user2ReceivedServerMessage if User 1 sent a message back
    }

    @Test
    fun `test sending message to offline user`() = testApplication {
        application { module(testing = true) }
        val client = createClient {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(testJson)
            }
        }

        val senderId = "senderOfflineTest_${System.currentTimeMillis()}"
        val offlineRecipientId = "offlineUser_${System.currentTimeMillis()}"

        client.webSocket("/chat") {
            // 1. Authenticate Sender
            sendSerialized(AuthRequest(userId = senderId, token = "token_sender", peerUserId = offlineRecipientId, publicKey = "pk_sender"))
            val authFrame = incoming.receive() as Frame.Text
            val authResponse = testJson.decodeFromString<SocketMessage>(authFrame.readText()) as AuthResponse
            assertTrue(authResponse.success, "Sender authentication failed.")

            // 2. Send message to an offline user
            val messageToOffline = ClientChatMessage(
            type = "",
            data = ChatMessageData(
                id = "1",
                senderId = "2",
                timestamp = System.currentTimeMillis().toString(),
                recipientId = "2",
                encryptedPayload = "", // Placeholder or actual encrypted
            )
        )
            sendSerialized(messageToOffline)

            // 3. Expect an ErrorMessage back
            val errorFrame = incoming.receive() as? Frame.Text
            assertNotNull(errorFrame, "Did not receive a response for offline message.")
            val errorMessage = testJson.decodeFromString<SocketMessage>(errorFrame.readText()) as? ErrorMessage

            assertNotNull(errorMessage, "Response was not an ErrorMessage.")
            assertTrue(errorMessage?.error?.contains("offline") ?: false ||
                    errorMessage?.error?.contains("not found") ?: false, "Unexpected error message for offline user: ${errorMessage.error}")
            assertEquals("Recipient $offlineRecipientId is offline or does not exist.", errorMessage?.error) // Adjust expected message as per your ChatRoutes
        }
    }

    @Test
    fun `test sending malformed JSON`() = testApplication {
        application { module(testing = true) }
        val client = createClient {
            install(WebSockets) // No specific content converter, send raw text
        }

        client.webSocket("/chat") {
            // Authenticate first to keep the connection open for the malformed message test
            val userId = "userMalformed_${System.currentTimeMillis()}"
            send(Frame.Text(testJson.encodeToString(AuthRequest(userId = userId, token = "token", peerUserId = "", publicKey = "pk"))))
            val authFrame = incoming.receive() as Frame.Text // Consume auth response
            val authResponse = testJson.decodeFromString<SocketMessage>(authFrame.readText()) as AuthResponse
            assertTrue(authResponse.success, "Auth failed, cannot proceed with malformed test")


            send(Frame.Text("{ not json at all"))

            val responseFrame = incoming.receive() as? Frame.Text
            assertNotNull(responseFrame, "Did not receive a response for malformed JSON.")
            val errorResponse: ErrorMessage? = try {
                testJson.decodeFromString<SocketMessage>(responseFrame.readText()) as? ErrorMessage
            } catch (e: Exception) {
                null
            }

            assertNotNull(errorResponse, "Response to malformed JSON was not a decodable ErrorMessage or was not an error message.")
            assertTrue(errorResponse?.error?.contains("Invalid message format") ?: false ||
                    errorResponse?.error?.contains("Cannot deserialize") ?: false, "Unexpected error message for malformed JSON: ${errorResponse?.error}")

            // Optionally, check if the connection is closed after sending too much garbage
            // val nextFrame = withTimeoutOrNull(500) { incoming.receiveCatching().getOrNull() }
            // assertTrue(nextFrame is Frame.Close || nextFrame == null, "Connection should be closed after malformed JSON.")
        }
    }

    // Helper to send serialized SocketMessage (if you don't use sendSerialized from Ktor client directly)
    // Ktor's sendSerialized should work if contentConverter is set up.
    // private suspend fun DefaultClientWebSocketSession.sendSerialized(message: SocketMessage) {
    //     send(Frame.Text(testJson.encodeToString(message)))
    // }
}