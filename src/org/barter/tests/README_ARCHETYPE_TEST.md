# Archetype User Generation and Similarity Test

## Overview

`TestArchetypeUsersGenAndSimilarity` is a comprehensive test suite designed to validate the user
matching algorithm by generating archetypical users with well-defined characteristics and analyzing
how accurately the semantic matching system identifies similar users.

## Purpose

Unlike random user generation, this test creates **100 users grouped into 10 distinct archetypes** (
10 users per archetype). This allows for:

1. **Validation of matching accuracy** - Similar users should match with each other
2. **Verification of semantic profiles** - Profile keywords and attributes should align
3. **Testing cross-archetype matching** - Users with complementary skills should be connected
4. **Quality assurance** - Ensures the algorithm works as intended before production

## Archetypes

### 1. Tech Nerds (10 users)

**Profile Keywords:**

- Technology/Learning: HIGH (0.9)
- Art/Philosophy: MEDIUM (0.4)
- Sports/Physical: LOW (-0.7)
- Social activities: LOW (-0.3)

**Providing:** Programming, Software Development, Web Development, Data Analysis, Technical Support,
etc.
**Seeking:** Graphic Design, Marketing, Social Media Management, Public Speaking, Networking

### 2. Athletes & Fitness Enthusiasts (10 users)

**Profile Keywords:**

- Sports/Physical: VERY HIGH (0.95)
- Nature/Outdoors: HIGH (0.7)
- Social activities: HIGH (0.6)
- Technology: LOW (-0.5)

**Providing:** Fitness Training, Sports Coaching, Nutrition Advice, Yoga, Running Partner, etc.
**Seeking:** Massage Therapy, Meal Preparation, Sports Equipment, Photography, Video Editing

### 3. Creative Artists (10 users)

**Profile Keywords:**

- Art/Philosophy: VERY HIGH (0.95)
- Social activities: MEDIUM (0.5)
- Business: LOW (-0.4)
- Sports: LOW (-0.3)

**Providing:** Painting, Drawing, Music Lessons, Graphic Design, Photography, Writing, etc.
**Seeking:** Exhibition Space, Art Supplies, Music Equipment, Marketing, Web Development

### 4. Eco Warriors & Nature Lovers (10 users)

**Profile Keywords:**

- Nature/Environment: VERY HIGH (0.95)
- Volunteering: HIGH (0.7)
- Physical activities: MEDIUM (0.4)
- Business: LOW (-0.6)

**Providing:** Gardening, Composting, Sustainable Living, Organic Farming, Environmental Education
**Seeking:** Land Access, Garden Tools, Seeds, Carpentry, Solar Panel Installation

### 5. Business Professionals (10 users)

**Profile Keywords:**

- Business: VERY HIGH (0.95)
- Social/Networking: HIGH (0.6)
- Technology: MEDIUM (0.5)
- Nature: LOW (-0.4)

**Providing:** Business Consulting, Financial Planning, Marketing Strategy, Project Management, etc.
**Seeking:** Web Development, Graphic Design, Content Writing, Social Media Management

### 6. Community Volunteers (10 users)

**Profile Keywords:**

- Volunteering: VERY HIGH (0.95)
- Social activities: HIGH (0.8)
- Nature: MEDIUM (0.4)
- Business: LOW (-0.3)

**Providing:** Tutoring, Mentoring, Childcare, Elderly Care, Community Organizing, etc.
**Seeking:** Transportation, Cooking, Home Repair, Legal Advice, Technical Support

### 7. Practical Makers & DIY Enthusiasts (10 users)

**Profile Keywords:**

- Physical work: HIGH (0.7)
- Technology: MEDIUM (0.4)
- Outdoors: MEDIUM (0.5)
- Crafts: MEDIUM (0.3)

**Providing:** Carpentry, Plumbing, Electrical Work, Car Repair, Bicycle Repair, etc.
**Seeking:** Design Services, Project Planning, Material Sourcing, Marketing

### 8. Social Butterflies (10 users)

**Profile Keywords:**

- Social activities: VERY HIGH (0.95)
- Physical/Partying: HIGH (0.6)
- Volunteering: MEDIUM (0.5)
- Technology: LOW (-0.2)

**Providing:** Event Planning, Party Organizing, Networking, Social Media Management, etc.
**Seeking:** Photography, DJ Services, Catering, Entertainment, Video Editing

### 9. Wellness & Spiritual Seekers (10 users)

**Profile Keywords:**

- Spirituality/Philosophy: HIGH (0.8)
- Nature: MEDIUM-HIGH (0.6)
- Volunteering: MEDIUM-HIGH (0.6)
- Yoga/Physical: MEDIUM (0.5)
- Business: LOW (-0.5)

**Providing:** Yoga, Meditation, Life Coaching, Massage Therapy, Reiki, Nutrition Counseling
**Seeking:** Studio Space, Music Therapy, Art Supplies, Garden Space, Healthy Cooking

### 10. Balanced Generalists (10 users)

**Profile Keywords:**

- All categories: MEDIUM (0.2-0.5)
- Well-rounded with no extreme preferences

**Providing:** General Help, Conversation, Pet Sitting, Cooking, Cleaning, Basic Tutoring
**Seeking:** Home Repair, Car Repair, Computer Help, Gardening Help, Moving Assistance

## Test Flow

```
1. Generate Users
   ├─► Create 10 users per archetype (100 total)
   ├─► Assign unique IDs and names
   └─► Place in Paris region (within 5km)

2. Create Profiles
   ├─► Set profile keywords based on archetype
   ├─► Add small random variation (±0.1)
   └─► Store in user_profiles table

3. Assign Attributes
   ├─► Add PROVIDING attributes from archetype definition
   ├─► Add SEEKING attributes from archetype definition
   └─► Set high relevancy (0.7-1.0)

4. Update Semantic Profiles
   ├─► Generate embedding_needs vector
   ├─► Generate embedding_haves vector
   └─► Generate embedding_profile vector

5. Test Matching
   ├─► Select 1 user from each archetype
   ├─► Find similar profiles (same PROVIDING)
   ├─► Find helpful profiles (complementary SEEKING/PROVIDING)
   └─► Analyze match quality

6. Generate Statistics
   ├─► Calculate same-archetype match percentage
   ├─► Show cross-archetype match patterns
   └─► Display matching matrix
```

## Expected Results

### Good Matching Indicators

1. **High Same-Archetype Matching**
    - Tech Nerds should match primarily with other Tech Nerds
    - Athletes should match with Athletes
    - Target: >60% of top 10 matches should be same archetype

2. **Semantic Score Alignment**
    - Same archetype matches should have scores >0.7
    - Different archetype matches should have lower scores
    - Complementary archetypes should appear in "helpful" results

3. **Profile Keyword Consistency**
    - Users with similar keyword weights should cluster together
    - High technology scores should group tech-related users
    - Physical activity scores should group active users

4. **Attribute Overlap**
    - Similar users should have overlapping PROVIDING attributes
    - Helpful users should PROVIDE what test user SEEKS

### Expected Cross-Archetype Patterns

Some archetypes naturally complement each other:

- **Tech Nerds ↔ Creative Artists**: Tech needs design, Artists need web development
- **Business Professionals ↔ Creative Artists**: Business needs marketing materials
- **Eco Warriors ↔ Practical Makers**: Gardeners need carpentry/plumbing
- **Athletes ↔ Wellness Seekers**: Complementary health/fitness services
- **Social Butterflies ↔ Creative Artists**: Event planning needs entertainment

## Output Analysis

### Per-Archetype Test Output

For each archetype, the test shows:

```
=== TESTING: Tech Nerds (Introverted, tech-savvy individuals...) ===

Test User (Tech Nerds)
  User ID: xxx
  Haves: Programming (0.85), Web Development (0.92), ...
  Needs: Graphic Design (0.78), Marketing (0.81), ...
  Profile Keywords: Technology: 0.88, Art: 0.42, Sports: -0.68, ...

--- SIMILAR PROFILES (Similar PROVIDING attributes) ---
Found 20 matching profiles:
  - Same archetype (Tech Nerds): 14 (70.0%)
  - Creative Artists: 3 matches (avg score: 0.65)
  - Business Professionals: 2 matches (avg score: 0.58)
  - Other: 1 match (avg score: 0.52)

Top 5 Matches:
1. [✓ SAME] Tech Nerds (Score: 0.892)
2. [✓ SAME] Tech Nerds (Score: 0.878)
3. [✓ SAME] Tech Nerds (Score: 0.854)
4. [✗ DIFF] Creative Artists (Score: 0.682)
5. [✓ SAME] Tech Nerds (Score: 0.847)

--- HELPFUL PROFILES (Their PROVIDING matches my SEEKING) ---
Found 18 matching profiles:
  - Creative Artists: 8 (44.4%)
  - Business Professionals: 5 (27.8%)
  - Social Butterflies: 3 (16.7%)
  ...
```

### Matching Statistics Summary

```
MATCHING STATISTICS SUMMARY
================================================================================

Archetype Matching Matrix (Top 10 matches per user):
Rows = Test User Archetype, Columns = Matched Archetype

Tech Nerds:
  → Tech Nerds: 14 matches (70.0%)
  → Creative Artists: 3 matches (15.0%)
  → Business Professionals: 2 matches (10.0%)
  → Balanced Generalists: 1 match (5.0%)

Athletes & Fitness Enthusiasts:
  → Athletes & Fitness Enthusiasts: 16 matches (80.0%)
  → Wellness & Spiritual Seekers: 2 matches (10.0%)
  → Social Butterflies: 1 match (5.0%)
  → Eco Warriors: 1 match (5.0%)

...
```

## Running the Test

### Method 1: Via Application.kt (Recommended)

1. Open `src/org/barter/Application.kt`
2. Uncomment the test line:
   ```kotlin
   runBlocking {
       AttributeCategorizer().initialize()
       attributesDao.populateMissingEmbeddings()
       TestArchetypeUsersGenAndSimilarity.execute()  // Uncomment this
   }
   ```
3. Run the application
4. Test executes on startup
5. Re-comment the line after testing

### Method 2: Direct Execution

```kotlin
import org.barter.tests.TestArchetypeUsersGenAndSimilarity

suspend fun runTest() {
    TestArchetypeUsersGenAndSimilarity.execute()
}
```

## Interpreting Results

### ✅ Good Results (Algorithm Working Correctly)

- **Same-archetype matching >60%**: Most matches are within the same group
- **High scores for similar users**: Scores >0.7 for same archetype
- **Logical cross-matches**: Complementary archetypes appear when appropriate
- **Profile keyword alignment**: Similar keywords = similar matches
- **Attribute overlap**: Matched users share relevant attributes

### ⚠️ Problematic Results (Needs Investigation)

- **Same-archetype matching <40%**: Random or poor matching
- **Low similarity scores**: Even same archetype scores <0.5
- **Illogical matches**: Unrelated archetypes matching frequently
- **Profile keyword mismatch**: Different keywords but high match scores
- **No attribute overlap**: Matched users have nothing in common

## Database Impact

**Tables Populated:**

- `user_registration_data`: 100 users
- `user_profiles`: 100 profiles with keywords
- `user_attributes`: ~1,200 attributes (12 per user average)
- `user_semantic_profiles`: 100 semantic vectors (3 per user: needs, haves, profile)

**Space Required:** ~5-10 MB

**Cleanup:** Delete all test users after testing:

```sql
DELETE FROM user_registration_data WHERE id IN (
  SELECT id FROM user_registration_data 
  WHERE public_key LIKE 'key_%'
);
```

## Differences from TestRandom100UsersGenAndSimilarity

| Aspect | Random Test | Archetype Test |
|--------|-------------|----------------|
| User Generation | Completely random | Structured archetypes |
| Attributes | Random selection | Curated per archetype |
| Profile Keywords | Random weights | Archetype-specific weights |
| Validation | Limited | Comprehensive analysis |
| Match Quality | Unpredictable | Measurable and expected |
| Use Case | Basic functionality | Algorithm validation |
| Output | Simple listing | Detailed statistics |
| Same-User Clustering | Unlikely | Expected and measured |

## Validation Checklist

Use this checklist to verify matching algorithm quality:

- [ ] Each archetype test shows >60% same-archetype matching
- [ ] Similarity scores for same archetype are >0.7
- [ ] Profile keywords align with matched users
- [ ] Helpful profiles provide what test user seeks
- [ ] Cross-archetype patterns make logical sense
- [ ] Tech Nerds match primarily with Tech Nerds
- [ ] Athletes match primarily with Athletes
- [ ] Creative Artists match primarily with Artists
- [ ] Business Professionals match with Business types
- [ ] Complementary archetypes appear in helpful results
- [ ] No completely random/unrelated matches in top 10
- [ ] Semantic scores decrease with dissimilarity
- [ ] Balanced Generalists show more diverse matches

## Future Enhancements

- [ ] Add more granular archetypes (e.g., Frontend Dev vs Backend Dev)
- [ ] Test geographic filtering within archetypes
- [ ] Measure matching speed/performance
- [ ] Test edge cases (users with minimal attributes)
- [ ] Add attribute weighting tests
- [ ] Test temporal aspects (recently active users)
- [ ] Validate caching effectiveness
- [ ] Test with 1000+ users for scalability

## Troubleshooting

### Issue: Low same-archetype matching

**Possible Causes:**

- Semantic profile generation not working correctly
- Profile keywords not being factored properly
- Attribute vectors not aligned
- Random variation too high (>±0.1)

**Solution:** Check `updateSemanticProfile` implementation and vector generation

### Issue: All matches have similar scores

**Possible Causes:**

- Vectors not normalized correctly
- Distance metric not sensitive enough
- All users too similar (unlikely with archetypes)

**Solution:** Review similarity calculation in `findProfilesBySemanticSimilarity`

### Issue: Profile keywords not affecting matches

**Possible Causes:**

- `embedding_profile` not being used in similarity query
- Profile keyword weights not being processed
- Wrong weighting in similarity formula (should be 30%)

**Solution:** Verify `buildProfileVectorSql` and similarity query weights

## Conclusion

This test suite provides a robust way to validate that the matching algorithm works as intended by
creating predictable user groups and measuring how accurately they are matched. High same-archetype
matching percentages indicate a well-functioning semantic matching system.
