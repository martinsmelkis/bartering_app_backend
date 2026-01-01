# Semantic Search Improvements for "Sink fixing" → DIY/Plumbing Matches

## Problem Analysis

Your search for "Sink fixing" wasn't finding matches like "Mechanics", "DIY", "Home renovation"
because of several issues:

### 1. **Too Strict Thresholds**

- **Old**: Semantic similarity threshold of 0.6-0.7
- **Reality**: Related-but-different concepts typically score 0.35-0.5
    - "Sink fixing" → "Plumbing": ~0.7-0.8 (very similar)
    - "Sink fixing" → "DIY": ~0.4-0.5 (related)
    - "Sink fixing" → "Home renovation": ~0.35-0.45 (loosely related)
    - "Sink fixing" → "Carpentry": ~0.25-0.35 (somewhat related)

### 2. **Two-Stage Search Logic Was Too Conservative**

- Only triggered semantic search if Stage 1 found < 10 results
- If keyword search found 10+ exact matches, semantic search never ran
- This meant you'd miss all the related-but-not-exact matches

### 3. **Averaging Diluted Strong Matches**

- Old approach: Average of (haves, needs, profile) embeddings
- Problem: If someone is great at "Plumbing" but their profile keywords are about "Cooking", the
  average drops significantly
- Better: Weight the **best match** more heavily (60%) + average (40%)

### 4. **Fuzzy Text Matching Too Strict**

- `word_similarity` threshold of 0.45 wouldn't catch many related terms
- Lowered to 0.35 to be more inclusive

## How Embeddings Actually Work

### ✅ What Embeddings ARE Good At:

- **Semantic similarity**: "Plumber" ↔ "Sink repair" (score: ~0.75)
- **Conceptual relatedness**: "DIY" ↔ "Home improvement" (score: ~0.65)
- **Domain clustering**: "Mechanic" ↔ "Car repair" ↔ "Auto service" (score: ~0.6-0.7)
- **Synonyms**: "Repair" ↔ "Fix" ↔ "Maintenance" (score: ~0.8)

### ❌ What Embeddings CANNOT Do:

- **Exact keyword matching**: Embeddings might miss exact brand names or specific tools
- **Multi-word phrase matching**: "vintage car restoration" might not match "vintage" + "car"
  separately
- **Logical operations**: Can't do "Plumbing AND NOT commercial plumbing"

### 🎯 The Hybrid Approach (What You Now Have):

1. **Stage 1**: Fast keyword/fuzzy search catches exact and near-exact matches
2. **Stage 2**: Semantic embedding search catches conceptually related matches
3. **Scoring Strategy**:
    - 60% weight to **BEST individual match** (catches specialists)
    - 40% weight to **average match** (provides stability)

## Changes Made

### 1. Lowered Similarity Thresholds

```kotlin
// Keyword score threshold
0.30 → 0.20  // Stage 1 filtering
0.20 → 0.15  // SQL WHERE clause

// Semantic score threshold  
0.60 → 0.35  // Now catches related concepts

// SQL semantic filter
0.0 → 0.25  // Filter out only very weak matches
```

### 2. Improved Two-Stage Logic

```kotlin
// OLD:
val useSemanticEnhancement = keywordResults.size < 10

// NEW:
val hasLowKeywordScores = keywordResults.any { it.second < 0.5 }
val useSemanticEnhancement = keywordResults.size < 20 || hasLowKeywordScores
```

### 3. Better Scoring: Best Match + Average

```kotlin
// OLD: Simple average
val score = validScores.average()

// NEW: Weighted combination
val bestScore = validScores.maxOrNull() ?: 0.0
val avgScore = validScores.average()
val finalScore = 0.6 * bestScore + 0.4 * avgScore
```

This ensures:

- A user with ONE strong match (0.7) ranks higher than one with three weak matches (0.4, 0.3, 0.3)
- But we still consider overall profile fit

### 4. Updated SQL Scoring Logic

Added `GREATEST()` function to find best individual embedding match:

```sql
0.6 * GREATEST(
    haves_similarity,
    needs_similarity,  
    profile_similarity
) + 0.4 * AVERAGE(...)
```

### 5. Lowered Fuzzy Matching Thresholds

```sql
word_similarity(?, text) > 0.45  →  > 0.35
similarity(?, text) > 0.45  →  > 0.35
```

## Expected Results Now

For "Sink fixing", you should now find:

| User Profile | Expected Score | Why |
|-------------|----------------|-----|
| Plumber | 0.75-0.85 | Direct semantic match |
| DIY enthusiast | 0.45-0.55 | Related domain |
| Home renovation | 0.40-0.50 | Related domain |
| Handyman | 0.50-0.60 | Related skills |
| Mechanic | 0.30-0.40 | Related (fixing things) but different domain |
| Car enthusiast | 0.25-0.35 | Loosely related (might be filtered) |

## Testing Recommendations

1. **Test with empty DB**: Add users with just these attributes:
    - User A: "Plumbing"
    - User B: "DIY projects"
    - User C: "Home renovation"
    - User D: "Sink repair"
    - Search for "Sink fixing" → Should find all 4

2. **Test score distribution**:
    - Print out the similarity scores to verify they're in expected ranges
    - User D should score highest (0.8+)
    - User A should be second (0.7+)
    - Users B & C should be 0.4-0.5

3. **Test threshold tuning**:
    - If getting too many irrelevant results → increase threshold to 0.4
    - If missing relevant results → decrease to 0.3
    - The 0.35 threshold is a reasonable starting point

## Further Improvements (Future)

1. **Category-based boosting**: If you know "Plumbing" and "Sink repair" are in the same category,
   boost that score
2. **User feedback loop**: Track which matches users click on, use that to tune thresholds
3. **Multi-language support**: Embeddings work across languages if using multilingual models
4. **Synonym expansion**: Pre-process search queries to add synonyms before embedding

## Why Embeddings Are Worth It

Even with these adjustments, embeddings provide massive value:

- **No manual synonym lists**: "Repair", "Fix", "Service", "Maintain" all match automatically
- **Conceptual understanding**: Understands "Car mechanic" is related to "Auto repair"
- **Typo tolerance**: "Plumer" still matches "Plumber" (when combined with fuzzy search)
- **Scales infinitely**: Works with millions of users without manual categorization

The key is **calibrating thresholds** based on your specific use case and data.
