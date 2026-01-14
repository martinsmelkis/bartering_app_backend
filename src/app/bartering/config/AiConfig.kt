package app.bartering.config

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

/**
 * Configuration object for AI-related settings (Ollama embeddings, etc.).
 *
 * This uses the Typesafe Config library to load settings from application.conf
 * or environment variables. For production, use environment variables:
 * - OLLAMA_HOST: The base URL for the Ollama service (e.g., "http://localhost:11434")
 * - OLLAMA_EMBED_MODEL: The embedding model name (e.g., "mxbai-embed-large")
 */
object AiConfig {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val config = ConfigFactory.load()

    /**
     * The base URL for the Ollama service.
     *
     * Defaults to "http://ollama:11434" for Docker environments.
     * For host networking mode (e.g., AlmaLinux servers with DNS issues),
     * set OLLAMA_HOST environment variable to "http://localhost:11434"
     */
    val ollamaHost: String by lazy {
        val host = System.getenv("OLLAMA_HOST")
            ?: config.getString("ai.ollama.host")
        log.info("Using Ollama host: $host")
        host
    }

    /**
     * The embedding model to use for generating embeddings.
     *
     * Defaults to "mxbai-embed-large".
     * Override with OLLAMA_EMBED_MODEL environment variable if needed.
     */
    val embedModel: String by lazy {
        val model = System.getenv("OLLAMA_EMBED_MODEL")
            ?: config.getString("ai.ollama.embedModel")
        log.info("Using Ollama embed model: $model")
        model
    }
}
