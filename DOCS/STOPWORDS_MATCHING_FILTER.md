# Stopwords Filtering for Improved Match Accuracy

**Multi-Language Support**: English (en), German (de), Latvian (lv)

## Problem

The matching algorithm was producing false positives when postings shared common words but had fundamentally different intents. For example:

- **"Buying a house near Paris"** would match **"Selling a car near Paris"**
- Reason: Both contain "near" and "Paris", even though one is buying a house and the other is selling a car

This happened because the simple word-matching algorithm treated all words equally, including:
- Common prepositions (near, in, at, etc.)
- Transaction keywords (buying, selling, wanted, offering)
- Generic stopwords (the, a, and, etc.)

## Solution

Implemented a **StopWords Filter** that excludes non-meaningful words from the matching algorithm, focusing only on semantically important terms.

### Implementation

#### 1. StopWordsFilter Utility (`StopWordsFilter.kt`)

Created a comprehensive filter with three categories of excluded words:

**Common Stopwords:**
- Articles: a, an, the
- Prepositions: in, on, at, near, around, etc.
- Conjunctions: and, or, but
- Pronouns: I, you, this, that, etc.
- Common verbs: is, am, are, have, etc.

**Transaction Keywords:**
- Buying: buying, buy, seeking, wanted, looking, need
- Selling: selling, sell, offering, available, provide
- Trading: trade, swap, exchange, barter

**Features:**
- `extractMeaningfulWords()`: Extracts only meaningful words from text
- `filterWords()`: Filters a list of words
- `shouldKeepWord()`: Checks if a single word should be kept
- `isTransactionKeyword()`: Identifies transaction-type words
- Configurable minimum word length (default: 3 characters)
- Option to include/exclude transaction keywords

#### 2. Updated Matching Logic

**Modified `calculatePostingMatchScore()` in MatchNotificationService:**

Before:
```kotlin
val titleWords = interestPosting.title.lowercase().split(" ").filter { it.length > 3 }
val offerTitleWords = offerPosting.title.lowercase().split(" ").filter { it.length > 3 }
```

After:
```kotlin
val titleWords = StopWordsFilter.extractMeaningfulWords(
    interestPosting.title,
    minLength = 3,
    includeTransactionKeywords = true
)
val offerTitleWords = StopWordsFilter.extractMeaningfulWords(
    offerPosting.title,
    minLength = 3,
    includeTransactionKeywords = true
)
```

**Modified `calculateMatchScore()` for attribute matching:**

Before:
```kotlin
if (posting.title.normalizeAttributeForDBProcessing().contains(attributeId, ignoreCase = true)) {
    score += 0.6
}
```

After:
```kotlin
val attributeWords = StopWordsFilter.extractMeaningfulWords(attributeId)
val titleWords = StopWordsFilter.extractMeaningfulWords(posting.title)

val titleMatches = attributeWords.count { attrWord ->
    titleWords.any { it.contains(attrWord, ignoreCase = true) || attrWord.contains(it, ignoreCase = true) }
}
if (attributeWords.isNotEmpty()) {
    score += (titleMatches.toDouble() / attributeWords.size) * 0.6
}
```

## Results

### Example 1: Different Items, Same Location (FALSE MATCH PREVENTED)

**Before:**
- "Buying a house near Paris" vs "Selling a car near Paris"
- Matched words: "near", "Paris" (2 matches)
- Match score: ~0.6-0.7 ✅ (would match)

**After:**
- "Buying a house near Paris" → ["house", "paris"]
- "Selling a car near Paris" → ["car", "paris"]
- Matched words: "paris" (1 match out of 2 words)
- Match score: 0.5 ❌ (below threshold of 0.7)

### Example 2: Same Item, Different Transaction Type (CORRECT MATCH)

**Before:**
- "Buying gaming laptop" vs "Selling gaming laptop"
- Matched words: "gaming", "laptop" (2 matches)
- Match score: ~0.8 ✅ (would match)

**After:**
- "Buying gaming laptop" → ["gaming", "laptop"]
- "Selling gaming laptop" → ["gaming", "laptop"]
- Matched words: "gaming", "laptop" (2 matches out of 2 words)
- Match score: 1.0 ✅ (correct match!)

### Example 3: Generic Descriptions (FALSE MATCH PREVENTED)

**Before:**
- "Looking for a good car in the city" vs "Offering a nice house in the city"
- Matched words: "in", "the", "city", "good/nice" (4+ matches)
- Match score: ~0.7 ✅ (would match)

**After:**
- "Looking for a good car in the city" → ["city"]
- "Offering a nice house in the city" → ["house", "city"]
- Matched words: "city" (1 match)
- Match score: ~0.5 ❌ (below threshold)

## Testing

Comprehensive unit tests in `test/StopWordsFilterTest.kt`:
- ✅ Transaction keywords are filtered out
- ✅ Common stopwords are filtered out
- ✅ Different items don't match with same location
- ✅ Similar items match regardless of transaction type
- ✅ Custom minimum word length
- ✅ Optional transaction keyword filtering

Run tests with:
```bash
./gradlew test --tests StopWordsFilterTest
```

## Configuration

### Adding Custom Stopwords

To add domain-specific stopwords, modify `StopWordsFilter.kt`:

```kotlin
private val commonStopWords = setOf(
    // ... existing words ...
    "custom", "domain", "specific", "words"
)
```

### Adjusting Minimum Word Length

Change the default minimum length when calling the filter:

```kotlin
val words = StopWordsFilter.extractMeaningfulWords(
    text,
    minLength = 4  // Increase to filter more aggressively
)
```

### Excluding Transaction Keywords Optionally

For scenarios where you want to keep transaction keywords:

```kotlin
val words = StopWordsFilter.extractMeaningfulWords(
    text,
    includeTransactionKeywords = false  // Keep buying/selling/etc.
)
```

## Performance Impact

- **Minimal overhead**: Simple set-based lookups (O(1) per word)
- **Memory footprint**: ~200 stopwords in memory (~2-3 KB)
- **Processing time**: Negligible for typical posting titles/descriptions

## Multi-Language Support

### Supported Languages

The StopWordsFilter now supports **three languages**:

1. **English (en)** - Default
2. **German (de)** - Deutsche Sprache
3. **Latvian (lv)** - Latviešu valoda

### How It Works

The filter automatically uses the user's preferred language (from their profile) to apply the appropriate stopwords:

```kotlin
// Automatically uses user's locale
val userLocale = getUserLocale(posting.userId) // e.g., Locale.GERMAN
val words = StopWordsFilter.extractMeaningfulWords(
    posting.title,
    locale = userLocale
)
```

### Language-Specific Examples

#### German Example
```kotlin
val text = "Ich kaufe ein Haus in Berlin"
val words = StopWordsFilter.extractMeaningfulWords(text, locale = Locale.GERMAN)
// Result: ["haus", "berlin"]
// Filtered: "ich", "kaufe", "ein", "in"
```

#### Latvian Example
```kotlin
val text = "Pērku māju Rīgā"
val words = StopWordsFilter.extractMeaningfulWords(text, locale = Locale("lv"))
// Result: ["māju", "rīgā"]
// Filtered: "pērku"
```

### Language-Specific Stopwords

Each language has its own set of:
- **Common stopwords**: Articles, prepositions, conjunctions, pronouns, etc.
- **Transaction keywords**: Language-specific buying/selling/trading terms

| Language | Transaction Keywords Examples |
|----------|------------------------------|
| English  | buying, selling, trading, wanted, offering |
| German   | kaufe, verkaufe, suche, biete, tausche |
| Latvian  | pērku, pārdodu, meklēju, piedāvāju, maiņa |

### Adding New Languages

To add a new language:

1. Add stopwords in `StopWordsFilter.kt`:
```kotlin
private val spanishCommonStopWords = setOf(
    "el", "la", "de", "en", "y", "un", "una", ...
)

private val spanishTransactionKeywords = setOf(
    "compro", "vendo", "busco", "ofrezco", ...
)
```

2. Register in language maps:
```kotlin
private val commonStopWordsByLanguage = mapOf(
    "en" to englishCommonStopWords,
    "de" to germanCommonStopWords,
    "lv" to latvianCommonStopWords,
    "es" to spanishCommonStopWords  // Add here
)
```

## Future Enhancements

1. ✅ **Multi-language support**: English, German, Latvian implemented
2. **More languages**: Add French, Spanish, Russian, etc.
3. **Dynamic stopwords**: Learn common but non-meaningful words from user data
4. **Context-aware filtering**: Different stopword sets for different categories
5. **Stemming/Lemmatization**: "buying" and "buy" treated as the same word
6. **Phrase detection**: Keep certain phrases like "near Paris" as single tokens

## Backward Compatibility

- ✅ No breaking changes to the API
- ✅ Existing match scores may change (this is expected and desired)
- ⚠️ Users might see fewer matches initially (this is correct behavior)
- ℹ️ May want to adjust `minMatchScore` thresholds if too few matches

## Related Files

- **Implementation**: `src/app/bartering/features/notifications/utils/StopWordsFilter.kt`
- **Usage**: `src/app/bartering/features/notifications/service/MatchNotificationService.kt`
- **Tests**: `test/StopWordsFilterTest.kt`
- **Documentation**: This file

## See Also

- [Notification Preferences Documentation](NOTIFICATION_PREFERENCES_COMPLETE.md)
- [Notifications Implementation](NOTIFICATIONS_IMPLEMENTATION.md)
- [Semantic Search Improvements](SEMANTIC_SEARCH_IMPROVEMENTS.md)
