# Keyword Search API - Implementation Summary

## Overview

A comprehensive keyword search API has been successfully implemented that allows searching for user
profiles using semantic similarity matching. The search compares input text against user attributes
and profile keywords using AI embeddings.

## What Was Implemented

### 1. Database Layer (DAO)

**File**: `src/org/barter/features/profile/dao/UserProfileDao.kt`

- Added interface method: `searchProfilesByKeyword()`

**File**: `src/org/barter/features/profile/dao/UserProfileDaoImpl.kt`

- Implemented `searchProfilesByKeyword()` method with:
    - Input validation and sanitization
    - SQL injection prevention
    - On-the-fly embedding generation using pgai/Ollama
    - Semantic similarity calculation across three embedding types:
        - `embedding_haves` (what users provide/offer)
        - `embedding_needs` (what users seek)
        - `embedding_profile` (user personality/interests)
    - Weighted scoring algorithm (40% haves, 40% needs, 20% profile)
    - Optional geographic location filtering
    - Result ranking by similarity and distance
    - Automatic filtering of low-quality matches (< 0.3 similarity)

### 2. API Route Layer

**File**: `src/org/barter/features/profile/routes/ProfileRoutes.kt`

- Created new route function: `searchProfilesByKeywordRoute()`
    - HTTP GET endpoint: `/api/v1/profiles/search`
    - Query parameters:
        - `q`, `query`, or `searchText` (required): search text
        - `lat` & `lon` (optional): location coordinates
        - `radius` (optional): search radius in meters
        - `limit` (optional, default 20): max results (1-100)
    - Parameter validation
    - Error handling with appropriate HTTP status codes
    - JSON response formatting

**File**: `src/org/barter/features/profile/routes/ProfileManagementRoutes.kt`

- Wired the new route into the routing configuration

### 3. Documentation

**File**: `KEYWORD_SEARCH_API.md`

- Complete API documentation including:
    - Endpoint details and parameters
    - Request/response formats
    - Matching algorithm explanation
    - Security considerations
    - Performance notes
    - Integration examples (JavaScript, Python, Kotlin)
    - Error handling guide
    - Use cases

**File**: `KEYWORD_SEARCH_TESTING.md`

- Comprehensive testing guide with:
    - Quick start tests (curl, Postman, browser)
    - Automated test examples
    - HTTP testing samples
    - Performance testing guidelines
    - Debugging tips
    - Common issues and solutions
    - Integration test examples

**File**: `KEYWORD_SEARCH_IMPLEMENTATION_SUMMARY.md` (this file)

- Implementation overview and summary

## Key Features

### Semantic Search

- Uses AI embeddings (nomic-embed-text via Ollama) for semantic understanding
- Matches meaning rather than exact keywords
- Example: searching "yoga" will also find "meditation", "wellness", "mindfulness"

### Multi-Aspect Matching

- Searches across three user profile dimensions:
    1. What users can provide (skills, services, items)
    2. What users are seeking (needs, wants)
    3. User profile keywords (interests, personality traits)

### Location Awareness

- Optional geographic filtering using PostGIS
- Combines semantic similarity with physical proximity
- Results include distance in kilometers

### Quality Filtering

- Automatically filters out poor matches (< 30% similarity)
- Configurable result limits (1-100)
- Sorted by relevance and distance

### Security

- Input validation and sanitization
- SQL injection prevention
- Parameterized queries
- Pattern matching for dangerous SQL keywords
- Length restrictions on input

## Technical Architecture

### Database Schema Used

```
user_registration_data
├─ id (UUID)
└─ publicKey

user_profiles
├─ user_id (FK to user_registration_data)
├─ name
├─ location (PostGIS geography)
└─ profile_keywords_with_weights (JSONB)

user_semantic_profiles
├─ user_id (PK/FK)
├─ embedding_profile (vector(768))
├─ embedding_haves (vector(768))
├─ embedding_needs (vector(768))
└─ updated_at

user_attributes
├─ user_id (FK)
├─ attribute_id
├─ type (ENUM: PROFILE, PROVIDING, SEEKING, SHARING)
├─ relevancy (DECIMAL)
└─ description
```

### Technologies Used

1. **Ktor**: Web framework for API routing
2. **Exposed**: Kotlin SQL framework
3. **PostgreSQL**: Main database
4. **pgvector**: Vector similarity search extension
5. **pgai**: AI/ML integration extension
6. **PostGIS**: Geospatial extension
7. **Ollama**: Local LLM/embedding service
8. **nomic-embed-text**: Embedding model (768 dimensions)
9. **Koin**: Dependency injection

### Query Flow

```
1. Client Request
   └─> GET /api/v1/profiles/search?q=yoga&lat=40.7128&lon=-74.0060

2. Route Handler (ProfileRoutes.kt)
   ├─> Validate parameters
   ├─> Extract query parameters
   └─> Call DAO method

3. DAO Layer (UserProfileDaoImpl.kt)
   ├─> Validate and sanitize input
   ├─> Build SQL query with CTEs
   ├─> Generate embedding for search text (via pgai/Ollama)
   ├─> Calculate similarities (cosine distance)
   ├─> Apply location filtering (if provided)
   ├─> Fetch matching profiles
   ├─> Fetch user attributes
   └─> Combine and return results

4. Database (PostgreSQL + Extensions)
   ├─> pgai generates embedding vector
   ├─> pgvector performs similarity search
   ├─> PostGIS handles location filtering
   └─> Returns ranked results

5. Response
   └─> JSON array of UserProfileWithDistance objects
```

## SQL Query Structure

The implementation uses a sophisticated SQL query with:

1. **CTE (Common Table Expression)** for search embedding generation
2. **Multiple JOINs** to combine user, profile, and semantic data
3. **Vector similarity calculations** using cosine distance operator (`<=>`)
4. **Weighted scoring** across three embedding types
5. **Geospatial filtering** using PostGIS ST_DWithin function
6. **Multi-criteria sorting** (similarity DESC, distance ASC)
7. **Result limiting** for performance

Example simplified query structure:

```sql
WITH search_embedding AS (
    SELECT ai.ollama_embed('nomic-embed-text', ?, ...) AS embedding
)
SELECT 
    u.id, up.name, up.location,
    -- Similarity calculations
    GREATEST(COALESCE(1 - (usp.embedding_haves <=> search.embedding), 0), 0) as haves_sim,
    GREATEST(COALESCE(1 - (usp.embedding_needs <=> search.embedding), 0), 0) as needs_sim,
    GREATEST(COALESCE(1 - (usp.embedding_profile <=> search.embedding), 0), 0) as profile_sim,
    -- Combined weighted score
    (0.4 * haves_sim + 0.4 * needs_sim + 0.2 * profile_sim) as combined_similarity,
    ST_Distance(up.location, ST_MakePoint(?, ?)) as distance_meters
FROM user_registration_data u
INNER JOIN user_profiles up ON u.id = up.user_id
INNER JOIN user_semantic_profiles usp ON u.id = usp.user_id
WHERE (usp.embedding_haves IS NOT NULL OR ...)
    AND ST_DWithin(up.location, ST_MakePoint(?, ?), ?)
ORDER BY combined_similarity DESC, distance_meters ASC
LIMIT ?;
```

## API Endpoint

### Request

```
GET /api/v1/profiles/search
```

### Parameters

| Parameter | Type | Required | Default | Constraints |
|-----------|------|----------|---------|-------------|
| q/query/searchText | String | Yes | - | 1-1000 chars |
| lat | Double | No | - | Must be with lon |
| lon | Double | No | - | Must be with lat |
| radius | Double | No | - | Meters, use with lat/lon |
| limit | Integer | No | 20 | 1-100 |

### Response

```typescript
UserProfileWithDistance[] = [
  {
    profile: {
      userId: string,
      name: string,
      latitude: number | null,
      longitude: number | null,
      attributes: Array<{
        attributeId: string,
        type: number,  // 0=PROFILE, 1=PROVIDING, 2=SEEKING, 3=SHARING
        relevancy: number,
        description: string | null
      }>,
      profileKeywordDataMap: { [key: string]: number } | null
    },
    distanceKm: number
  }
]
```

## Performance Characteristics

### Expected Performance

- **Simple search** (no location): 100-300ms
- **Search with location**: 150-400ms
- **Large result sets** (limit=100): 200-500ms

### Optimization Features

- Vector indexes on embedding columns (pgvector IVFFlat or HNSW)
- Spatial indexes on location column (PostGIS GIST)
- Result limit to prevent overwhelming queries
- Similarity threshold to filter poor matches early
- Efficient JOIN strategy

### Scalability Considerations

- Embedding generation happens once per search (not per user)
- Vector similarity is O(n) but optimized with indexes
- Location filtering uses spatial indexes (very fast)
- Database-side computation reduces network overhead

## Security Measures

1. **Input Validation**
    - Length restrictions (1-1000 characters)
    - Pattern matching for SQL injection attempts
    - Numeric range validation

2. **SQL Injection Prevention**
    - Parameterized queries throughout
    - String sanitization (escaping quotes, backslashes)
    - Pattern detection for dangerous SQL keywords

3. **Output Sanitization**
    - Generic error messages (no internal details exposed)
    - Consistent error response format

4. **Rate Limiting** (recommended for production)
    - Not implemented yet, but should be added
    - Suggest: 100 requests per minute per IP

## Testing

### Manual Testing

- ✅ Build successful (Gradle build passed)
- ⏳ Runtime testing needed (start server and test endpoints)

### Test Commands

```bash
# Basic search
curl "http://localhost:8081/api/v1/profiles/search?q=yoga"

# With location
curl "http://localhost:8081/api/v1/profiles/search?q=programming&lat=40.7128&lon=-74.0060&radius=5000"

# With limit
curl "http://localhost:8081/api/v1/profiles/search?q=teaching&limit=10"
```

### Expected Test Results

- Non-empty results for common keywords
- Empty array for unlikely keywords
- Distance values populated when location provided
- Results sorted by similarity
- Limit constraint respected

## Integration Points

### Existing APIs

The keyword search complements existing profile APIs:

- `GET /api/v1/profiles/nearby` - Location-only search
- `POST /api/v1/profile-info` - Get specific profile
- `GET /api/v1/profiles/similar` - Find similar profiles to a user
- `GET /api/v1/profiles/helpful` - Find helpful profiles for a user

### Client Integration

Can be integrated into:

- Mobile app search functionality
- Web app search bar
- Discovery/explore features
- Recommendation systems
- Matching algorithms

## Files Modified/Created

### Modified Files

1. `src/org/barter/features/profile/dao/UserProfileDao.kt` (added interface method)
2. `src/org/barter/features/profile/dao/UserProfileDaoImpl.kt` (added implementation)
3. `src/org/barter/features/profile/routes/ProfileRoutes.kt` (added route handler)
4. `src/org/barter/features/profile/routes/ProfileManagementRoutes.kt` (wired route)

### Created Files

1. `KEYWORD_SEARCH_API.md` (comprehensive API documentation)
2. `KEYWORD_SEARCH_TESTING.md` (testing guide)
3. `KEYWORD_SEARCH_IMPLEMENTATION_SUMMARY.md` (this file)

### Unchanged Files

- No database migrations needed (uses existing tables)
- No model changes required (uses existing DTOs)
- No configuration changes needed

## Usage Examples

### Kotlin (Client)

```kotlin
val results = userProfileDao.searchProfilesByKeyword(
    searchText = "yoga teacher",
    latitude = 40.7128,
    longitude = -74.0060,
    radiusMeters = 5000.0,
    limit = 10
)

results.forEach { profile ->
    println("${profile.profile.name} - ${profile.distanceKm} km away")
}
```

### JavaScript/TypeScript

```javascript
const response = await fetch(
  'http://localhost:8081/api/v1/profiles/search?q=yoga&limit=10'
);
const profiles = await response.json();
```

### Python

```python
import requests

response = requests.get(
    'http://localhost:8081/api/v1/profiles/search',
    params={'q': 'programming', 'limit': 10}
)
profiles = response.json()
```

## Future Enhancements

### Short Term

1. Add authentication/authorization
2. Implement request rate limiting
3. Add search analytics/logging
4. Cache frequently searched terms
5. Add pagination support

### Medium Term

1. Support for multiple search terms with boolean operators
2. Fuzzy matching for typos/misspellings
3. Search filters (by attribute type, category, etc.)
4. Personalized results based on user history
5. Search suggestions/autocomplete

### Long Term

1. Advanced ranking with machine learning
2. A/B testing different similarity weights
3. Real-time search as user types
4. Natural language query understanding
5. Multi-language support

## Deployment Checklist

Before deploying to production:

- [ ] Test with real user data
- [ ] Verify Ollama service is production-ready
- [ ] Set up monitoring for search performance
- [ ] Add request rate limiting
- [ ] Configure appropriate result limits
- [ ] Test with high load/concurrent users
- [ ] Set up error alerting
- [ ] Document API in main documentation
- [ ] Create client SDK/library
- [ ] Add search analytics

## Support and Maintenance

### Monitoring

Monitor these metrics:

- Search query latency (p50, p95, p99)
- Embedding generation time
- Database query time
- Error rate
- Popular search terms
- No-result queries

### Troubleshooting

Common issues:

1. Slow searches → Check indexes, Ollama performance
2. No results → Verify semantic profiles exist, check similarity threshold
3. Errors → Check Ollama connectivity, database connections

### Maintenance Tasks

- Regularly update embeddings as user profiles change
- Monitor and optimize database indexes
- Review and adjust similarity threshold
- Update Ollama models as needed
- Clean up old/stale semantic profiles

## Conclusion

The keyword search API is now fully implemented and ready for testing. It provides:

- ✅ Semantic search across user profiles
- ✅ Multi-aspect matching (haves, needs, profile)
- ✅ Location-aware filtering
- ✅ Quality result ranking
- ✅ Security hardening
- ✅ Comprehensive documentation
- ✅ Testing examples

The implementation follows best practices for:

- Code organization
- Security
- Performance
- Maintainability
- Documentation

Next steps:

1. Start the application
2. Test the API endpoint
3. Verify results with sample data
4. Fine-tune similarity weights if needed
5. Deploy to staging environment
6. Gather user feedback
7. Iterate and improve

## Contact & Questions

For questions or issues regarding this implementation:

- Review the API documentation: `KEYWORD_SEARCH_API.md`
- Check the testing guide: `KEYWORD_SEARCH_TESTING.md`
- Examine the code in: `src/org/barter/features/profile/dao/UserProfileDaoImpl.kt`
- Test the endpoint: `GET /api/v1/profiles/search`
