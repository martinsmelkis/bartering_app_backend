package app.bartering.features.notifications.utils

import java.util.Locale

/**
 * Utility for filtering stopwords and common words that should not contribute to matching
 * Supports multiple languages: English (en), German (de), Latvian (lv)
 */
object StopWordsFilter {
    
    // ==================== ENGLISH (en) ====================
    
    private val englishCommonStopWords = setOf(
        // Articles
        "a", "an", "the",
        
        // Prepositions
        "in", "on", "at", "to", "for", "of", "with", "from", "by", "about",
        "near", "around", "between", "through", "during", "before", "after",
        "above", "below", "under", "over",
        
        // Conjunctions
        "and", "or", "but", "nor", "yet", "so",
        
        // Pronouns
        "i", "you", "he", "she", "it", "we", "they", "this", "that", "these", "those",
        "my", "your", "his", "her", "its", "our", "their",
        
        // Common verbs
        "is", "am", "are", "was", "were", "be", "been", "being", "have", "has", "had",
        "do", "does", "did", "will", "would", "should", "could", "may", "might", "must",
        "can",
        
        // Quantifiers
        "all", "some", "any", "many", "much", "few", "more", "most", "less", "least",
        "several", "each", "every",
        
        // Other common words
        "very", "just", "only", "also", "too", "well", "good", "new", "old",
        "first", "last", "other", "same", "different", "own"
    )
    
    private val englishTransactionKeywords = setOf(
        // Buying/seeking keywords
        "buying", "buy", "seeking", "wanted", "looking", "need", "needs", "needed",
        "searching", "search", "want", "wants", "wish", "wishlist", "interested",
        "desire", "require", "requires", "required",
        
        // Selling/offering keywords
        "selling", "sell", "offering", "offer", "offers", "available", "provide",
        "providing", "give", "giving", "have", "supply", "supplying",
        
        // Trade keywords
        "trade", "trading", "swap", "swapping", "exchange", "exchanging", "barter", "bartering"
    )
    
    // ==================== GERMAN (de) ====================
    
    private val germanCommonStopWords = setOf(
        // Articles
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer", "einen", "einem", "eines",
        
        // Prepositions
        "in", "im", "an", "am", "auf", "bei", "mit", "von", "vom", "zu", "zum", "zur", "nach",
        "über", "unter", "vor", "hinter", "neben", "zwischen", "durch", "für", "gegen",
        "ohne", "um", "aus",
        
        // Conjunctions
        "und", "oder", "aber", "doch", "jedoch", "sondern", "denn", "weil", "dass", "ob",
        
        // Pronouns
        "ich", "du", "er", "sie", "es", "wir", "ihr", "mein", "dein", "sein", "ihr", "unser",
        "dieser", "diese", "dieses", "jener", "welcher", "alle", "man",
        
        // Common verbs
        "ist", "bin", "bist", "sind", "war", "waren", "sein", "haben", "hat", "hatte", "hatten",
        "werden", "wird", "wurde", "wurden", "können", "kann", "könnte", "müssen", "muss",
        "sollen", "soll", "wollen", "will", "möchte", "mögen", "mag",
        
        // Quantifiers
        "alle", "einige", "viele", "wenige", "mehr", "weniger", "viel", "wenig",
        "mehrere", "jede", "jeder", "jedes",
        
        // Other common words
        "sehr", "nur", "auch", "noch", "schon", "gut", "neue", "neuer", "neues", "alte",
        "alter", "altes", "erste", "erster", "erstes", "letzte", "letzter", "letztes",
        "andere", "anderer", "anderes", "gleiche", "gleicher", "gleiches"
    )
    
    private val germanTransactionKeywords = setOf(
        // Buying/seeking keywords
        "kaufe", "kaufen", "suche", "suchen", "gesucht", "brauche", "brauchen", "benötige",
        "benötigen", "benötigt", "will", "wollen", "möchte", "möchten", "interessiert",
        "interesse", "wunsch", "wünsche",
        
        // Selling/offering keywords
        "verkaufe", "verkaufen", "verkauf", "biete", "bieten", "anbiete", "anbieten",
        "angebot", "angebote", "verfügbar", "gebe", "geben", "habe", "haben",
        
        // Trade keywords
        "tausche", "tauschen", "tausch", "austausch", "austauschen"
    )
    
    // ==================== LATVIAN (lv) ====================
    
    private val latvianCommonStopWords = setOf(
        // Articles (Latvian doesn't have articles, but demonstratives)
        "šis", "šī", "šo", "tas", "tā", "to",
        
        // Prepositions
        "ar", "bez", "caur", "dēļ", "gar", "no", "pa", "par", "pār", "pēc", "pie", "pirms",
        "pret", "priekš", "starp", "uz", "virs", "zem", "līdz", "ap", "aiz", "iekš",
        
        // Conjunctions
        "un", "vai", "bet", "ka", "jo", "tomēr", "tāpēc", "gan", "arī", "kā",
        
        // Pronouns
        "es", "tu", "viņš", "viņa", "mēs", "jūs", "viņi", "viņas", "mans", "tavs",
        "sava", "savs", "mūsu", "jūsu", "kas", "kurš", "kura", "visi", "visas",
        
        // Common verbs
        "ir", "esmu", "esi", "esam", "esat", "bija", "būt", "būs", "tiek", "tika",
        "var", "varu", "vari", "varam", "varat", "varēja", "varētu", "vajag", "vajadzētu",
        "grib", "gribu", "gribi", "gribam", "gribat",
        
        // Quantifiers
        "visi", "daži", "daudz", "maz", "vairāk", "mazāk", "vairāki", "katrs", "katra",
        "ikkatrs", "abi", "abas",
        
        // Other common words
        "ļoti", "tikai", "arī", "vēl", "jau", "labs", "laba", "jauns", "jauna", "vecs",
        "veca", "pirmais", "pirmā", "pēdējais", "pēdējā", "cits", "cita", "tāds", "tāda"
    )
    
    private val latvianTransactionKeywords = setOf(
        // Buying/seeking keywords
        "pērku", "pirkt", "meklēju", "meklē", "vajag", "vajadzīgs", "vajadzīga", "nepieciešams",
        "nepieciešama", "vēlos", "vēlas", "interesē", "interesējos", "vēlme",
        
        // Selling/offering keywords
        "pārdodu", "pārdod", "pārdot", "piedāvāju", "piedāvā", "piedāvāt", "piedāvājums",
        "pieejams", "pieejama", "dodu", "dot", "ir",
        
        // Trade keywords
        "mainīt", "maiņa", "apmainīt", "apmaiņa", "maiņu"
    )
    
    // ==================== LANGUAGE MAPS ====================
    
    private val commonStopWordsByLanguage = mapOf(
        "en" to englishCommonStopWords,
        "de" to germanCommonStopWords,
        "lv" to latvianCommonStopWords
    )
    
    private val transactionKeywordsByLanguage = mapOf(
        "en" to englishTransactionKeywords,
        "de" to germanTransactionKeywords,
        "lv" to latvianTransactionKeywords
    )
    
    /**
     * Get all stopwords for a specific language
     */
    private fun getAllStopWords(languageCode: String, includeTransactionKeywords: Boolean = true): Set<String> {
        val common = commonStopWordsByLanguage[languageCode] ?: englishCommonStopWords
        val transaction = if (includeTransactionKeywords) {
            transactionKeywordsByLanguage[languageCode] ?: englishTransactionKeywords
        } else {
            emptySet()
        }
        return common + transaction
    }
    
    // ==================== BACKWARD COMPATIBILITY ====================
    
    /**
     * Combined set of all English stopwords (for backward compatibility)
     */
    @Deprecated("Use getAllStopWords(languageCode) instead", ReplaceWith("getAllStopWords(\"en\", includeTransactionKeywords)"))
    private val allStopWords = englishCommonStopWords + englishTransactionKeywords
    
    // ==================== PUBLIC API ====================
    
    /**
     * Filter a list of words to remove stopwords
     * 
     * @param words List of words to filter
     * @param minLength Minimum word length to keep (default 3)
     * @param includeTransactionKeywords Whether to filter out transaction keywords (default true)
     * @param locale Locale to determine which language stopwords to use (default: English)
     * @return Filtered list of words
     */
    fun filterWords(
        words: List<String>,
        minLength: Int = 3,
        includeTransactionKeywords: Boolean = true,
        locale: Locale = Locale.ENGLISH
    ): List<String> {
        val languageCode = locale.language
        val stopWordsToUse = getAllStopWords(languageCode, includeTransactionKeywords)
        
        return words
            .filter { it.length >= minLength }
            .filter { it.lowercase() !in stopWordsToUse }
    }
    
    /**
     * Filter a single word
     * 
     * @param word Word to check
     * @param minLength Minimum word length to keep (default 3)
     * @param includeTransactionKeywords Whether to filter out transaction keywords (default true)
     * @param locale Locale to determine which language stopwords to use (default: English)
     * @return true if word should be kept, false if it should be filtered out
     */
    fun shouldKeepWord(
        word: String,
        minLength: Int = 3,
        includeTransactionKeywords: Boolean = true,
        locale: Locale = Locale.ENGLISH
    ): Boolean {
        if (word.length < minLength) return false
        
        val languageCode = locale.language
        val stopWordsToUse = getAllStopWords(languageCode, includeTransactionKeywords)
        
        return word.lowercase() !in stopWordsToUse
    }
    
    /**
     * Extract meaningful words from a text string
     * 
     * @param text Text to process
     * @param minLength Minimum word length to keep (default 3)
     * @param includeTransactionKeywords Whether to filter out transaction keywords (default true)
     * @param locale Locale to determine which language stopwords to use (default: English)
     * @return List of meaningful words
     */
    fun extractMeaningfulWords(
        text: String,
        minLength: Int = 3,
        includeTransactionKeywords: Boolean = true,
        locale: Locale = Locale.ENGLISH
    ): List<String> {
        val words = text.lowercase()
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        
        return filterWords(words, minLength, includeTransactionKeywords, locale)
    }
    
    /**
     * Check if a word is a transaction keyword
     * 
     * @param word Word to check
     * @param locale Locale to determine which language to check (default: English)
     */
    fun isTransactionKeyword(word: String, locale: Locale = Locale.ENGLISH): Boolean {
        val languageCode = locale.language
        val keywords = transactionKeywordsByLanguage[languageCode] ?: englishTransactionKeywords
        return word.lowercase() in keywords
    }
    
    /**
     * Get all transaction keywords for a language (for debugging/testing)
     * 
     * @param locale Locale to determine which language keywords to retrieve
     */
    fun getTransactionKeywords(locale: Locale = Locale.ENGLISH): Set<String> {
        val languageCode = locale.language
        return transactionKeywordsByLanguage[languageCode] ?: englishTransactionKeywords
    }
    
    /**
     * Get all common stopwords for a language (for debugging/testing)
     * 
     * @param locale Locale to determine which language stopwords to retrieve
     */
    fun getCommonStopWords(locale: Locale = Locale.ENGLISH): Set<String> {
        val languageCode = locale.language
        return commonStopWordsByLanguage[languageCode] ?: englishCommonStopWords
    }
    
    /**
     * Get list of supported language codes
     */
    fun getSupportedLanguages(): Set<String> {
        return commonStopWordsByLanguage.keys
    }
}
