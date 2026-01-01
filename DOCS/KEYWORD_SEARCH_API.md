# Keyword Search API Documentation

## Overview

The Keyword Search API allows you to search for user profiles using semantic similarity matching. It
compares the search text against user attributes, profile keywords, and what users are providing or
seeking, returning a ranked list of matching profiles.

## Endpoint

```
GET /api/v1/profiles/search
```

## Authentication

This endpoint is currently **unauthenticated** and publicly accessible. You may want to add
authentication in production environments.

## Query Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `q`, `query`, or `searchText` | String | Yes | - | The search text to match against user profiles. Maximum 1000 characters. |
| `lat` | Double | No | - | Latitude for location-based filtering. Must be provided with `lon`. |
| `lon` | Double | No | - | Longitude for location-based filtering. Must be provided with `lat`. |
| `radius` | Double | No | - | Search radius in meters for location filtering. Only used if `lat` and `lon` are provided. |
| `limit` | Integer | No | 20 | Maximum number of results to return. Must be between 1 and 100. |

## Request Examples

### Basic keyword search

```
GET /api/v1/profiles/search?q=yoga teacher
```

### Search with location filtering

```
GET /api/v1/profiles/search?q=programming&lat=40.7128&lon=-74.0060&radius=5000
```

### Search with custom limit

```
GET /api/v1/profiles/search?q=guitar lessons&limit=10
```

### Search with all parameters

```
GET /api/v1/profiles/search?q=web development&lat=51.5074&lon=-0.1278&radius=10000&limit=50
```

## Response Format

Returns an array of `UserProfileWithDistance` objects, sorted by semantic similarity score (
descending) and distance (ascending).

```json
[
  {
    "profile": {
      "userId": "123e4567-e89b-12d3-a456-426614174000",
      "name": "John Doe",
      "latitude": 40.7128,
      "longitude": -74.0060,
      "attributes": [
        {
          "attributeId": "yoga",
          "type": 1,
          "relevancy": 0.95,
          "description": "Certified yoga instructor with 5 years experience"
        },
        {
          "attributeId": "meditation",
          "type": 1,
          "relevancy": 0.85,
          "description": null
        }
      ],
      "profileKeywordDataMap": {
        "teaching": 0.9,
        "wellness": 0.8,
        "mindfulness": 0.7
      }
    },
    "distanceKm": 2.5
  },
  {
    "profile": {
      "userId": "987f6543-e21b-45c3-d654-321876543210",
      "name": "Jane Smith",
      "latitude": 40.7589,
      "longitude": -73.9851,
      "attributes": [
        {
          "attributeId": "fitness",
          "type": 0,
          "relevancy": 0.88,
          "description": "Personal trainer specializing in yoga"
        }
      ],
      "profileKeywordDataMap": {
        "fitness": 0.95,
        "health": 0.85
      }
    },
    "distanceKm": 5.8
  }
]
```

## Response Fields

### UserProfileWithDistance

| Field | Type | Description |
|-------|------|-------------|
| `profile` | UserProfile | The user's profile information |
| `distanceKm` | Double | Distance from search location in kilometers (0 if no location provided) |

### UserProfile

| Field | Type | Description |
|-------|------|-------------|
| `userId` | String | Unique user identifier (UUID) |
| `name` | String | User's display name |
| `latitude` | Double? | User's latitude (nullable) |
| `longitude` | Double? | User's longitude (nullable) |
| `attributes` | Array | List of user attributes |
| `profileKeywordDataMap` | Map<String, Double>? | Profile keywords with relevancy scores |

### UserAttributeDto

| Field | Type | Description |
|-------|------|-------------|
| `attributeId` | String | Attribute identifier |
| `type` | Integer | Attribute type: 0=PROFILE, 1=PROVIDING, 2=SEEKING, 3=SHARING |
| `relevancy` | Double | Relevancy score (0.0 to 1.0) |
| `description` | String? | Optional attribute description |

## Matching Algorithm

The search uses semantic similarity matching with the following weighted scoring:

- **40% weight**: Similarity to what users are **providing/offering** (their skills, services,
  items)
- **40% weight**: Similarity to what users are **seeking/needing** (what they're looking for)
- **20% weight**: Similarity to user **profile keywords** (personality traits, interests)

### Similarity Threshold

Results with a combined similarity score below 0.3 (30%) are automatically filtered out to ensure
quality matches.

## How It Works

1. The search text is converted into an embedding vector using the `nomic-embed-text` model via
   Ollama
2. This embedding is compared against three types of user embeddings:
    - `embedding_haves`: What the user can provide
    - `embedding_needs`: What the user is seeking
    - `embedding_profile`: User's personality/interest profile
3. Cosine similarity is calculated for each comparison
4. A weighted average produces the final similarity score
5. Results are ranked by similarity score (descending) and distance (ascending)

## Error Responses

### 400 Bad Request

Missing or invalid parameters:

```json
{
  "error": "Missing required parameter 'q', 'query', or 'searchText'"
}
```

```json
{
  "error": "Limit must be between 1 and 100"
}
```

```json
{
  "error": "Both 'lat' and 'lon' must be provided together"
}
```

### 500 Internal Server Error

Server-side error during search:

```json
{
  "error": "An error occurred while searching profiles"
}
```

## Security Considerations

The implementation includes several security measures:

1. **Input validation**: Search text is limited to 1000 characters
2. **SQL injection prevention**: Input is sanitized and checked for dangerous SQL patterns
3. **Parameter validation**: Numeric parameters are validated for valid ranges
4. **Parameterized queries**: All SQL queries use parameterized statements

## Performance Considerations

- The search uses pgvector's cosine distance operator (`<=>`) which is optimized with indexes
- Embedding generation happens on-the-fly for the search text
- Results are limited to prevent overwhelming responses
- Location filtering uses PostGIS spatial indexes for efficient geospatial queries

## Use Cases

1. **Skill matching**: Find users who can teach specific skills
2. **Service discovery**: Locate users offering specific services
3. **Community building**: Connect users with similar interests
4. **Needs matching**: Find users seeking what others provide
5. **Location-based discovery**: Combine semantic and geographic search

## Example Integration

### JavaScript/TypeScript

```javascript
async function searchProfiles(searchText, options = {}) {
  const params = new URLSearchParams({
    q: searchText,
    limit: options.limit || 20
  });
  
  if (options.latitude && options.longitude) {
    params.append('lat', options.latitude);
    params.append('lon', options.longitude);
    if (options.radius) {
      params.append('radius', options.radius);
    }
  }
  
  const response = await fetch(
    `https://your-api.com/api/v1/profiles/search?${params}`
  );
  
  if (!response.ok) {
    throw new Error('Search failed');
  }
  
  return await response.json();
}

// Usage
const results = await searchProfiles('yoga teacher', {
  latitude: 40.7128,
  longitude: -74.0060,
  radius: 5000,
  limit: 10
});
```

### Python

```python
import requests

def search_profiles(search_text, latitude=None, longitude=None, 
                   radius=None, limit=20):
    params = {
        'q': search_text,
        'limit': limit
    }
    
    if latitude and longitude:
        params['lat'] = latitude
        params['lon'] = longitude
        if radius:
            params['radius'] = radius
    
    response = requests.get(
        'https://your-api.com/api/v1/profiles/search',
        params=params
    )
    response.raise_for_status()
    return response.json()

# Usage
results = search_profiles(
    'programming',
    latitude=40.7128,
    longitude=-74.0060,
    radius=10000
)
```

### Kotlin (Android)

```kotlin
data class SearchOptions(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radius: Double? = null,
    val limit: Int = 20
)

suspend fun searchProfiles(
    searchText: String,
    options: SearchOptions = SearchOptions()
): List<UserProfileWithDistance> {
    val url = buildString {
        append("https://your-api.com/api/v1/profiles/search")
        append("?q=${URLEncoder.encode(searchText, "UTF-8")}")
        append("&limit=${options.limit}")
        options.latitude?.let { append("&lat=$it") }
        options.longitude?.let { append("&lon=$it") }
        options.radius?.let { append("&radius=$it") }
    }
    
    val response = httpClient.get(url)
    return response.body()
}

// Usage
val results = searchProfiles(
    "web development",
    SearchOptions(
        latitude = 51.5074,
        longitude = -0.1278,
        radius = 10000.0,
        limit = 20
    )
)
```

## Implementation Details

### Database Query

The implementation generates an embedding for the search text on-the-fly and compares it against
user semantic profiles stored in the database. The query:

1. Creates a CTE with the search embedding
2. Joins user registration, profiles, and semantic profiles tables
3. Calculates three similarity scores (haves, needs, profile)
4. Computes a weighted combined similarity score
5. Optionally filters by geographic location
6. Orders by similarity and distance
7. Limits the results

### Dependencies

- **pgvector**: PostgreSQL extension for vector similarity search
- **pgai**: PostgreSQL AI extension for embedding generation
- **PostGIS**: PostgreSQL extension for geospatial queries
- **Ollama**: Local embedding model service (nomic-embed-text)

## Future Enhancements

Potential improvements to consider:

1. Add authentication to track search history
2. Implement search result caching
3. Add filters for attribute types (only PROVIDING, only SEEKING, etc.)
4. Support for multiple search terms with boolean operators
5. Fuzzy matching for misspellings
6. Search result personalization based on user history
7. Add pagination for large result sets
8. Include similarity scores in the response
9. Support for searching within specific categories

## Related APIs

- `GET /api/v1/profiles/nearby` - Find profiles by geographic location only
- `POST /api/v1/profile-info` - Get detailed information for a specific profile
- `GET /api/v1/profiles/similar` - Find semantically similar profiles to a specific user
- `GET /api/v1/profiles/helpful` - Find profiles that can help a specific user

## Version History

- **v1.0.0** (Initial Release)
    - Basic keyword search functionality
    - Semantic similarity matching
    - Optional location filtering
    - Configurable result limits

Consider:

1. Adding monitoring/metrics for search performance
2. Implementing search analytics (popular terms, no-result queries)
3. Creating a dashboard to visualize search patterns
4. Setting up alerts for slow queries or errors
5. Adding caching for frequently searched terms
6. Implementing search suggestions/autocomplete
