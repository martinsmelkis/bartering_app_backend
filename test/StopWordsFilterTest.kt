import app.bartering.features.notifications.utils.StopWordsFilter
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test suite for StopWordsFilter
 * Demonstrates how stopwords filtering prevents false matches
 * Tests multi-language support (English, German, Latvian)
 */
class StopWordsFilterTest {
    
    @Test
    fun `test transaction keywords are filtered out`() {
        val text = "buying a house near Paris"
        val words = StopWordsFilter.extractMeaningfulWords(text)
        
        // "buying", "near" should be filtered out, only "house" and "paris" remain
        assertEquals(2, words.size)
        assertTrue(words.contains("house"))
        assertTrue(words.contains("paris"))
        assertFalse(words.contains("buying"))
        assertFalse(words.contains("near"))
    }
    
    @Test
    fun `test selling keywords are filtered out`() {
        val text = "selling a car near Paris"
        val words = StopWordsFilter.extractMeaningfulWords(text)
        
        // "selling", "near" should be filtered out, only "car" and "paris" remain
        assertEquals(2, words.size)
        assertTrue(words.contains("car"))
        assertTrue(words.contains("paris"))
        assertFalse(words.contains("selling"))
        assertFalse(words.contains("near"))
    }
    
    @Test
    fun `test different items don't match even with same location`() {
        val buyingHouse = "buying a house near Paris"
        val sellingCar = "selling a car near Paris"
        
        val buyingWords = StopWordsFilter.extractMeaningfulWords(buyingHouse)
        val sellingWords = StopWordsFilter.extractMeaningfulWords(sellingCar)
        
        // Both have "paris" but "house" != "car"
        // Calculate overlap (similar to what matching does)
        val overlap = buyingWords.count { word -> 
            sellingWords.any { it.contains(word, ignoreCase = true) || word.contains(it, ignoreCase = true) }
        }
        
        // Only 1 word matches (paris), not enough for a good match
        assertEquals(1, overlap)
        
        // Match score would be 1/2 = 0.5, which is below the typical threshold
        val matchScore = overlap.toDouble() / buyingWords.size
        assertTrue(matchScore < 0.7) // Typical minimum threshold
    }
    
    @Test
    fun `test similar items match even with different transaction type`() {
        val buyingLaptop = "buying gaming laptop in Paris"
        val sellingLaptop = "selling gaming laptop in Paris"
        
        val buyingWords = StopWordsFilter.extractMeaningfulWords(buyingLaptop)
        val sellingWords = StopWordsFilter.extractMeaningfulWords(sellingLaptop)
        
        // Both should have "gaming", "laptop", "paris"
        val overlap = buyingWords.count { word -> 
            sellingWords.any { it.contains(word, ignoreCase = true) || word.contains(it, ignoreCase = true) }
        }
        
        // All meaningful words match
        assertEquals(3, overlap)
        
        // Match score would be 3/3 = 1.0
        val matchScore = overlap.toDouble() / buyingWords.size
        assertTrue(matchScore >= 0.7) // Good match!
    }
    
    @Test
    fun `test common stopwords are filtered`() {
        val text = "I am looking for a good and very nice house in the city"
        val words = StopWordsFilter.extractMeaningfulWords(text)
        
        // Should filter: "looking", "for", "good", "and", "very", "nice", "the"
        // Should keep: "house", "city"
        assertTrue(words.contains("house"))
        assertTrue(words.contains("city"))
        assertFalse(words.contains("and"))
        assertFalse(words.contains("the"))
        assertFalse(words.contains("very"))
        assertFalse(words.contains("looking"))
    }
    
    @Test
    fun `test isTransactionKeyword identifies transaction words`() {
        assertTrue(StopWordsFilter.isTransactionKeyword("buying"))
        assertTrue(StopWordsFilter.isTransactionKeyword("selling"))
        assertTrue(StopWordsFilter.isTransactionKeyword("wanted"))
        assertTrue(StopWordsFilter.isTransactionKeyword("offering"))
        assertTrue(StopWordsFilter.isTransactionKeyword("trade"))
        
        assertFalse(StopWordsFilter.isTransactionKeyword("house"))
        assertFalse(StopWordsFilter.isTransactionKeyword("car"))
        assertFalse(StopWordsFilter.isTransactionKeyword("paris"))
    }
    
    @Test
    fun `test filterWords with custom minLength`() {
        val words = listOf("I", "am", "buying", "a", "car")
        
        // With minLength = 3, should filter out "I", "am", "a" and "buying"
        val filtered = StopWordsFilter.filterWords(words, minLength = 3)
        
        assertEquals(1, filtered.size)
        assertTrue(filtered.contains("car"))
    }
    
    @Test
    fun `test excluding transaction keywords can be disabled`() {
        val text = "buying selling trading"
        
        // With transaction keywords filtering
        val wordsWithFilter = StopWordsFilter.extractMeaningfulWords(
            text,
            includeTransactionKeywords = true
        )
        assertEquals(0, wordsWithFilter.size)
        
        // Without transaction keywords filtering (they're still short, so won't match minLength)
        // But let's test with shouldKeepWord
        assertTrue(StopWordsFilter.shouldKeepWord("buying", minLength = 3, includeTransactionKeywords = false))
        assertFalse(StopWordsFilter.shouldKeepWord("buying", minLength = 3, includeTransactionKeywords = true))
    }
    
    // ==================== GERMAN LANGUAGE TESTS ====================
    
    @Test
    fun `test German transaction keywords are filtered out`() {
        val text = "Ich kaufe ein Haus in Berlin"
        val locale = Locale.GERMAN
        val words = StopWordsFilter.extractMeaningfulWords(text, locale = locale)
        
        // "Ich", "kaufe", "ein", "in" should be filtered out
        // Only "Haus" and "Berlin" remain
        assertEquals(2, words.size)
        assertTrue(words.contains("haus"))
        assertTrue(words.contains("berlin"))
        assertFalse(words.contains("kaufe"))
        assertFalse(words.contains("ein"))
    }
    
    @Test
    fun `test German selling keywords are filtered out`() {
        val text = "Verkaufe Auto in Berlin"
        val locale = Locale.GERMAN
        val words = StopWordsFilter.extractMeaningfulWords(text, locale = locale)
        
        // "Verkaufe", "in" should be filtered out
        // "Auto" and "Berlin" remain
        assertEquals(2, words.size)
        assertTrue(words.contains("auto"))
        assertTrue(words.contains("berlin"))
        assertFalse(words.contains("verkaufe"))
    }
    
    @Test
    fun `test German different items don't match with same location`() {
        val buyingHouse = "Kaufe Haus in Berlin"
        val sellingCar = "Verkaufe Auto in Berlin"
        val locale = Locale.GERMAN
        
        val buyingWords = StopWordsFilter.extractMeaningfulWords(buyingHouse, locale = locale)
        val sellingWords = StopWordsFilter.extractMeaningfulWords(sellingCar, locale = locale)
        
        // buyingWords: ["haus", "berlin"]
        // sellingWords: ["auto", "berlin"]
        // Only "berlin" matches
        val overlap = buyingWords.count { word -> 
            sellingWords.any { it.contains(word, ignoreCase = true) || word.contains(it, ignoreCase = true) }
        }
        
        assertEquals(1, overlap)
        val matchScore = overlap.toDouble() / buyingWords.size
        assertTrue(matchScore < 0.7)
    }
    
    @Test
    fun `test German similar items match correctly`() {
        val buying = "Suche Gaming Laptop in München"
        val selling = "Verkaufe Gaming Laptop in München"
        val locale = Locale.GERMAN
        
        val buyingWords = StopWordsFilter.extractMeaningfulWords(buying, locale = locale)
        val sellingWords = StopWordsFilter.extractMeaningfulWords(selling, locale = locale)
        
        // Both should have: ["gaming", "laptop", "münchen"]
        val overlap = buyingWords.count { word -> 
            sellingWords.any { it.contains(word, ignoreCase = true) || word.contains(it, ignoreCase = true) }
        }
        
        assertEquals(3, overlap)
        val matchScore = overlap.toDouble() / buyingWords.size
        assertTrue(matchScore >= 0.7)
    }
    
    @Test
    fun `test German transaction keyword detection`() {
        val locale = Locale.GERMAN
        
        assertTrue(StopWordsFilter.isTransactionKeyword("kaufe", locale))
        assertTrue(StopWordsFilter.isTransactionKeyword("verkaufe", locale))
        assertTrue(StopWordsFilter.isTransactionKeyword("suche", locale))
        assertTrue(StopWordsFilter.isTransactionKeyword("biete", locale))
        assertTrue(StopWordsFilter.isTransactionKeyword("tausche", locale))
        
        assertFalse(StopWordsFilter.isTransactionKeyword("haus", locale))
        assertFalse(StopWordsFilter.isTransactionKeyword("auto", locale))
    }
    
    // ==================== LATVIAN LANGUAGE TESTS ====================
    
    @Test
    fun `test Latvian transaction keywords are filtered out`() {
        val text = "Pērku māju Rīgā"
        val locale = Locale("lv")
        val words = StopWordsFilter.extractMeaningfulWords(text, locale = locale)
        
        // "Pērku" should be filtered out
        // "māju" and "Rīgā" remain
        assertEquals(2, words.size)
        assertTrue(words.contains("māju"))
        assertTrue(words.contains("rīgā"))
        assertFalse(words.contains("pērku"))
    }
    
    @Test
    fun `test Latvian selling keywords are filtered out`() {
        val text = "Pārdodu auto Rīgā"
        val locale = Locale("lv")
        val words = StopWordsFilter.extractMeaningfulWords(text, locale = locale)
        
        // "Pārdodu" should be filtered out
        // "auto" and "Rīgā" remain
        assertEquals(2, words.size)
        assertTrue(words.contains("auto"))
        assertTrue(words.contains("rīgā"))
        assertFalse(words.contains("pārdodu"))
    }
    
    @Test
    fun `test Latvian different items don't match with same location`() {
        val buying = "Meklēju māju Rīgā"
        val selling = "Pārdodu auto Rīgā"
        val locale = Locale("lv")
        
        val buyingWords = StopWordsFilter.extractMeaningfulWords(buying, locale = locale)
        val sellingWords = StopWordsFilter.extractMeaningfulWords(selling, locale = locale)
        
        // buyingWords: ["māju", "rīgā"]
        // sellingWords: ["auto", "rīgā"]
        // Only "rīgā" matches
        val overlap = buyingWords.count { word -> 
            sellingWords.any { it.contains(word, ignoreCase = true) || word.contains(it, ignoreCase = true) }
        }
        
        assertEquals(1, overlap)
        val matchScore = overlap.toDouble() / buyingWords.size
        assertTrue(matchScore < 0.7)
    }
    
    @Test
    fun `test Latvian similar items match correctly`() {
        val buying = "Meklēju spēļu datoru Rīgā"
        val selling = "Piedāvāju spēļu datoru Rīgā"
        val locale = Locale("lv")
        
        val buyingWords = StopWordsFilter.extractMeaningfulWords(buying, locale = locale)
        val sellingWords = StopWordsFilter.extractMeaningfulWords(selling, locale = locale)
        
        // Both should have: ["spēļu", "datoru", "rīgā"]
        val overlap = buyingWords.count { word -> 
            sellingWords.any { it.contains(word, ignoreCase = true) || word.contains(it, ignoreCase = true) }
        }
        
        assertEquals(3, overlap)
        val matchScore = overlap.toDouble() / buyingWords.size
        assertTrue(matchScore >= 0.7)
    }
    
    @Test
    fun `test Latvian transaction keyword detection`() {
        val locale = Locale("lv")
        
        assertTrue(StopWordsFilter.isTransactionKeyword("pērku", locale))
        assertTrue(StopWordsFilter.isTransactionKeyword("pārdodu", locale))
        assertTrue(StopWordsFilter.isTransactionKeyword("meklēju", locale))
        assertTrue(StopWordsFilter.isTransactionKeyword("piedāvāju", locale))
        assertTrue(StopWordsFilter.isTransactionKeyword("maiņa", locale))
        
        assertFalse(StopWordsFilter.isTransactionKeyword("māja", locale))
        assertFalse(StopWordsFilter.isTransactionKeyword("auto", locale))
    }
    
    // ==================== MULTI-LANGUAGE SUPPORT TESTS ====================
    
    @Test
    fun `test supported languages include en, de, lv`() {
        val supportedLanguages = StopWordsFilter.getSupportedLanguages()
        
        assertTrue(supportedLanguages.contains("en"))
        assertTrue(supportedLanguages.contains("de"))
        assertTrue(supportedLanguages.contains("lv"))
        assertEquals(3, supportedLanguages.size)
    }
    
    @Test
    fun `test default locale is English when unsupported language provided`() {
        val text = "buying a house"
        val unsupportedLocale = Locale("fr") // French not supported
        
        // Should fall back to English stopwords
        val words = StopWordsFilter.extractMeaningfulWords(text, locale = unsupportedLocale)
        
        // "buying", "a" should be filtered (English stopwords)
        assertEquals(1, words.size)
        assertTrue(words.contains("house"))
    }
}
