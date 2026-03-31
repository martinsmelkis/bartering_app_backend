package app.bartering.localization

import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap

object Localization {

    private const val BUNDLE_BASE_NAME = "locales.messages"
    private val bundles = ConcurrentHashMap<Locale, ResourceBundle>()

    // Cache for translation lookups: normalized search term -> English result (or NULL_SENTINEL for misses)
    private val translationCache = ConcurrentHashMap<String, String?>()
    private const val NULL_SENTINEL = "__NULL_SENTINEL__"
    private const val MAX_CACHE_SIZE = 1000

    private fun getBundle(locale: Locale): ResourceBundle {
        return bundles.computeIfAbsent(locale) {
            ResourceBundle.getBundle(BUNDLE_BASE_NAME, it, Utf8Control())
        }
    }

    fun getString(key: String, locale: Locale = Locale.ENGLISH, vararg args: Any): String {
        return try {
            String.format(locale, getBundle(locale).getString(key), *args)
        } catch (_: Exception) {
            // Fallback to English if key not found in specified locale or other error
            try {
                String.format(Locale.ENGLISH, getBundle(Locale.ENGLISH).getString(key), *args)
            } catch (_: Exception) {
                key // return key as last resort
            }
        }
    }

    /**
     * Reverse lookup: Find the English key that corresponds to a translated value.
     * Searches through all available locale bundles to find a match.
     * Results are cached to avoid repeated expensive lookups.
     * 
     * @param searchText The translated text (e.g., "Celtniecība" in Latvian)
     * @return The English value for the matching key (e.g., "Construction") or null if not found
     */
    fun findEnglishForTranslation(searchText: String): String? {
        val normalizedSearch = searchText.lowercase(Locale.ROOT).trim()
        
        // Check cache first (including negative cache for misses)
        val cachedResult = translationCache[normalizedSearch]
        if (cachedResult != null) {
            return if (cachedResult == NULL_SENTINEL) null else cachedResult
        }
        
        // Prevent cache from growing too large (simple LRU eviction)
        if (translationCache.size >= MAX_CACHE_SIZE) {
            // Clear oldest 25% of entries when max size reached
            val keysToRemove = translationCache.keys.take(MAX_CACHE_SIZE / 4)
            keysToRemove.forEach { translationCache.remove(it) }
        }
        
        // Perform the lookup
        val result = performTranslationLookup(normalizedSearch)
        
        // Cache the result (use sentinel for nulls to cache misses too)
        translationCache[normalizedSearch] = result ?: NULL_SENTINEL
        
        return result
    }
    
    /**
     * Internal method that performs the actual translation lookup.
     * Separated from caching logic for clarity.
     */
    private fun performTranslationLookup(normalizedSearch: String): String? {
        // Locales to check (add more as needed)
        val localesToCheck = listOf(
            Locale.forLanguageTag("lv"), // Latvian
            Locale.forLanguageTag("de"), // German
            // Add more locales here
        )
        
        for (locale in localesToCheck) {
            try {
                val bundle = getBundle(locale)
                
                // Iterate through all keys in the bundle
                val keys = bundle.keys
                while (keys.hasMoreElements()) {
                    val key = keys.nextElement()
                    val value = bundle.getString(key).lowercase(Locale.ROOT).trim()
                    
                    // Check for exact or partial match
                    if (value == normalizedSearch || 
                        value.contains(normalizedSearch) || 
                        normalizedSearch.contains(value)) {
                        
                        // Found match - return the English translation of this key
                        return try {
                            getString(key, Locale.ENGLISH)
                        } catch (_: Exception) {
                            // If English not available, use the key itself (removing attr_ prefix)
                            key.replace("attr_", "").replace("_", " ")
                        }
                    }
                }
            } catch (_: Exception) {
                // Bundle not available or other error, continue to next locale
            }
        }
        
        return null
    }

}