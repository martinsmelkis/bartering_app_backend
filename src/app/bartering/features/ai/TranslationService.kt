package app.bartering.features.ai

import app.bartering.config.AiConfig
import app.bartering.extensions.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.slf4j.LoggerFactory

/**
 * Service for translating text using AI (Ollama via pgai).
 * Uses PostgreSQL pgai extension for translation operations.
 */
class TranslationService {
    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Translates the given text to the target language using Ollama via pgai.
     * This is intended for offline/batch translation during profile updates,
     * not for real-time search operations.
     *
     * @param text The text to translate
     * @param targetLanguage The target language code (e.g., "en", "lv", "de")
     * @return The translated text, or null if translation fails
     */
    suspend fun translate(text: String, targetLanguage: String): String? {
        if (text.isBlank()) return text

        // Skip translation if text appears to already be in target language
        // This is a simple heuristic - can be improved
        return try {
            dbQuery {
                val translationPrompt = buildTranslationPrompt(text, targetLanguage)

                val translationSql = """
                    SELECT ai.ollama_generate(
                        '${AiConfig.translationModel}',
                        ?,
                        host => '${AiConfig.ollamaHost}'
                    ) as translated_text
                """.trimIndent()

                var translatedText: String? = null

                TransactionManager.current().connection.prepareStatement(translationSql, false)
                    .also { statement ->
                        statement[1] = translationPrompt
                        statement.executeQuery().use { rs ->
                            if (rs.next()) {
                                translatedText = rs.getString("translated_text")?.trim()
                            }
                        }
                    }

                translatedText?.takeIf { it.isNotBlank() && it != text }
            }
        } catch (e: Exception) {
            log.warn("Translation failed for text '{}...' to language '{}': {}",
                text.take(50), targetLanguage, e.message)
            null
        }
    }

    /**
     * Batch translates multiple texts. More efficient for profile updates with multiple attributes.
     *
     * @param texts List of texts to translate
     * @param targetLanguage The target language code
     * @return Map of original text to translated text (null if translation failed)
     */
    suspend fun translateBatch(texts: List<String>, targetLanguage: String): Map<String, String?> {
        if (texts.isEmpty()) return emptyMap()

        val results = mutableMapOf<String, String?>()
        texts.distinct().forEach { text ->
            results[text] = translate(text, targetLanguage)
        }
        return results
    }

    private fun buildTranslationPrompt(text: String, targetLanguage: String): String {
        val languageName = getLanguageName(targetLanguage)
        return """Translate the following text to $languageName. 
        |Respond with ONLY the translated text, no explanations, no quotes, no additional formatting.
        |
        |Text to translate: $text""".trimMargin()
    }

    private fun getLanguageName(languageCode: String): String {
        return when (languageCode.lowercase()) {
            "en" -> "English"
            "lv" -> "Latvian"
            "de" -> "German"
            "es" -> "Spanish"
            "fr" -> "French"
            "ru" -> "Russian"
            "lt" -> "Lithuanian"
            "et" -> "Estonian"
            else -> languageCode.uppercase()
        }
    }
}
