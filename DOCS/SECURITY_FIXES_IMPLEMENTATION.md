# Security Fixes Implementation Guide

## ✅ Completed Fixes

### 1. Security Utils Created

**File**: `src/org/barter/utils/SecurityUtils.kt`

- ✅ Input validation functions
- ✅ SQL injection pattern detection
- ✅ UUID validation
- ✅ Email validation
- ✅ Generic error responses

### 2. UserProfileDaoImpl - Partial Fix Applied

**File**: `src/org/barter/features/profile/dao/UserProfileDaoImpl.kt`

**Completed**:

- ✅ Added import for SecurityUtils
- ✅ Added UUID validation in `findProfilesBySemanticSimilarity`
- ✅ Added UUID validation in `updateSemanticProfile`
- ✅ Changed SQL queries to use `?` placeholders instead of string interpolation
- ✅ Updated parameter binding for semantic similarity query

**Remaining** (Line 418-422):

```kotlin
// CURRENT (has syntax error):
TransactionManager.current().connection.prepareStatement(finalUpsertQuery, false).also { statement ->
    statement.setString(1, userId)  // ← Wrong method
    statement.execute()
}

// SHOULD BE:
TransactionManager.current().connection.prepareStatement(finalUpsertQuery, false).use { statement ->
    statement[1] = userId  // ← Correct Exposed syntax
    statement.executeUpdate()
}
```

---

## 🔴 Critical Fixes Still Needed

### 3. User Profile DAO - Fix Statement Execution

**File**: `src/org/barter/features/profile/dao/UserProfileDaoImpl.kt`  
**Line**: ~420

**Find**:

```kotlin
TransactionManager.current().connection.prepareStatement(finalUpsertQuery, false).also { statement ->
    statement.setString(1, userId)
    statement.execute()
}
```

**Replace with**:

```kotlin
TransactionManager.current().connection.prepareStatement(finalUpsertQuery, false).use { statement ->
    statement[1] = userId
    statement.executeUpdate()
}
```

---

### 4. UserProfileDaoImpl - Fix Profile Embedding SQL Injection

**File**: `src/org/barter/features/profile/dao/UserProfileDaoImpl.kt`  
**Line**: ~445

**Current (VULNERABLE)**:

```kotlin
val finalUpsertQuery = """
    INSERT INTO user_semantic_profiles (user_id, embedding_profile)
    VALUES ('$userId', ($vectorSql))  -- ← SQL INJECTION
    ...
""".trimIndent()
TransactionManager.current().exec(finalUpsertQuery)
```

**Fix**:

```kotlin
val finalUpsertQuery = """
    INSERT INTO user_semantic_profiles (user_id, embedding_profile)
    VALUES (?, ($vectorSql))  -- ← Use parameter
    ...
""".trimIndent()
TransactionManager.current().connection.prepareStatement(finalUpsertQuery, false).use { statement ->
    statement[1] = userId
    statement.executeUpdate()
}
```

---

### 5. AttributesDaoImpl - Fix Multiple SQL Injections

**File**: `src/org/barter/features/attributes/dao/AttributesDaoImpl.kt`

**A. Line ~57-63 - populateMissingEmbeddings (VULNERABLE)**:

```kotlin
// CURRENT:
val embeddingSql = """
    UPDATE attributes
    SET embedding = ai.ollama_embed(
        'nomic-embed-text',
        '${key.replace("'", "''")}', -- ← Still vulnerable
        ...
    WHERE attribute_key = '${key.replace("'", "''")}'
""".trimIndent()
TransactionManager.current().exec(embeddingSql)
```

**Fix**:

```kotlin
val embeddingSql = """
    UPDATE attributes
    SET embedding = ai.ollama_embed(
        'nomic-embed-text',
        ?,
        host => 'http://ollama:11434'
    )
    WHERE attribute_key = ?
""".trimIndent()
TransactionManager.current().connection.prepareStatement(embeddingSql, false).use { statement ->
    statement[1] = key
    statement[2] = key
    statement.executeUpdate()
}
```

**B. Line ~89-96 - createAttributeWithCategories (VULNERABLE)**:

```kotlin
// CURRENT:
val embeddingSql = """
    UPDATE attributes
    SET embedding = ai.ollama_embed(
        'nomic-embed-text',
        '${customUserAttrText}',  -- ← SQL INJECTION
        ...
    WHERE id = ${newAttributeId}  -- ← SQL INJECTION
""".trimIndent()
```

**Fix**:

```kotlin
val embeddingSql = """
    UPDATE attributes
    SET embedding = ai.ollama_embed(
        'nomic-embed-text',
        ?,
        host => 'http://ollama:11434'
    )
    WHERE id = ?
""".trimIndent()
TransactionManager.current().connection.prepareStatement(embeddingSql, false).use { statement ->
    statement[1] = customUserAttrText
    statement[2] = newAttributeId
    statement.executeUpdate()
}
```

**C. Line ~301 - buildProfileVectorSql (VULNERABLE)**:

```kotlin
// CURRENT:
fun getEmbeddingSql(text: String): String {
    return "ai.ollama_embed('nomic-embed-text', " +
            "'${text.replace("'", "''")}', " +  -- ← Still vulnerable to advanced attacks
            "host => 'http://ollama:11434')"
}
```

**Note**: This is harder to fix with parameters since it's building SQL dynamically.  
**Options**:

1. Use whitelist validation for allowed text
2. Use more robust escaping
3. Refactor to use separate embedding service

**Temporary Fix**:

```kotlin
fun getEmbeddingSql(text: String): String {
    // Validate input
    require(text.length < 10000) { "Text too long" }
    require(!SecurityUtils.containsSqlInjectionPatterns(text)) { "Invalid text content" }
    
    val sanitized = SecurityUtils.sanitizeSqlString(text)
    return "ai.ollama_embed('nomic-embed-text', '$sanitized', host => 'http://ollama:11434')"
}
```

---

### 6. Application.kt - Fix CORS Configuration

**File**: `src/org/barter/Application.kt`  
**Line**: ~79

**Current (VULNERABLE)**:

```kotlin
install(CORS) {
    anyHost() // ← Allows ALL origins
    allowCredentials = true
}
```

**Fix**:

```kotlin
install(CORS) {
    // Whitelist specific origins
    val allowedOrigins = listOf(
        "http://localhost:3000",
        "http://localhost:8080",
        "https://your-production-domain.com"
    )
    
    allowedOrigins.forEach { origin ->
        allowHost(origin, schemes = listOf("http", "https"))
    }
    
    // Or use environment variable:
    val allowedOrigin = System.getenv("ALLOWED_ORIGIN") ?: "http://localhost:3000"
    allowHost(allowedOrigin.removePrefix("http://").removePrefix("https://"), 
              schemes = listOf("http", "https"))
    
    allowMethod(HttpMethod.Options)
    allowMethod(HttpMethod.Post)
    allowMethod(HttpMethod.Get)
    allowMethod(HttpMethod.Put)
    allowMethod(HttpMethod.Delete)
    
    allowHeader("X-User-ID")
    allowHeader("X-Timestamp")
    allowHeader("X-Signature")
    allowHeader(HttpHeaders.ContentType)
    allowHeader(HttpHeaders.Authorization)
    
    allowCredentials = true
}
```

---

### 7. SignatureVerification.kt - Improve Error Handling

**File**: `src/org/barter/utils/SignatureVerification.kt`  
**Line**: ~79

**Current**:

```kotlin
call.respond(HttpStatusCode.InternalServerError, "Signature verification failed: ${e.message}")
```

**Fix**:

```kotlin
// Log the detailed error internally
println("Signature verification error for user $userId: ${e.message}")
e.printStackTrace()

// Send generic error to client
SecurityUtils.respondWithGenericError(call, HttpStatusCode.Unauthorized)
return Pair(null, null)
```

**Line**: ~54 - Reduce Timestamp Window:

```kotlin
// CURRENT:
if (abs(currentTime - timestamp) > 300000) // 5 minutes

// FIX:
if (abs(currentTime - timestamp) > 120000) // 2 minutes (recommended)
```

---

### 8. Chat Routes - Secure Error Messages

**File**: `src/org/barter/features/chat/routes/ChatRoutes.kt`

**Multiple locations - Generic pattern**:

```kotlin
// CURRENT:
ErrorMessage("Invalid auth format: ${e.localizedMessage}")
ErrorMessage("Error processing message: ${e.localizedMessage}")

// FIX:
// Log detailed error
println("Chat error for user $userId: ${e.localizedMessage}")
// Send generic error
ErrorMessage("Authentication failed")
ErrorMessage("Message processing failed")
```

---

## 🟡 Medium Priority Fixes

### 9. Add Rate Limiting

**File**: `src/org/barter/Application.kt`

Add rate limiting plugin:

```kotlin
dependencies {
    implementation("io.ktor:ktor-server-rate-limit:$ktor_version")
}
```

```kotlin
install(RateLimit) {
    global {
        rateLimiter(limit = 100, refillPeriod = 60.seconds)
    }
    register(RateLimitName("auth")) {
        rateLimiter(limit = 10, refillPeriod = 60.seconds)
    }
}

// Apply to routes:
routing {
    rateLimit(RateLimitName("auth")) {
        post("/api/auth/login") {
            // ...
        }
    }
}
```

### 10. Add Input Validation

**All API endpoints**

Add validation before processing:

```kotlin
// Example for profile update:
post("/api/profile/update") {
    val request = call.receive<UserProfileUpdateRequest>()
    
    // Validate
    require(SecurityUtils.isValidLength(request.name, 1, 100)) { "Invalid name length" }
    require(request.latitude == null || SecurityUtils.isValidNumber(request.latitude, -90.0, 90.0)) { "Invalid latitude" }
    require(request.longitude == null || SecurityUtils.isValidNumber(request.longitude, -180.0, 180.0)) { "Invalid longitude" }
    
    // Process...
}
```

### 11. Add Security Headers

**File**: `src/org/barter/Application.kt`

```kotlin
install(DefaultHeaders) {
    header("X-Frame-Options", "DENY")
    header("X-Content-Type-Options", "nosniff")
    header("X-XSS-Protection", "1; mode=block")
    header("Referrer-Policy", "strict-origin-when-cross-origin")
    header("Content-Security-Policy", "default-src 'self'")
    // For HTTPS only:
    // header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
}
```

---

## Testing Commands

### Test SQL Injection Protection

```bash
# Test with malicious userId
curl -X POST http://localhost:8081/api/profile/similar \
  -H "Content-Type: application/json" \
  -d '{"userId": "'; DROP TABLE users; --"}'

# Should return: 400 Bad Request or validation error
```

### Test CORS Protection

```bash
# Test with unauthorized origin
curl -H "Origin: https://evil.com" \
     -H "Access-Control-Request-Method: POST" \
     -X OPTIONS http://localhost:8081/api/profile/update

# Should NOT include Access-Control-Allow-Origin in response
```

### Test Rate Limiting (after implementation)

```bash
# Send 20 requests rapidly
for i in {1..20}; do
  curl http://localhost:8081/api/auth/login &
done

# Should start returning 429 Too Many Requests after limit
```

---

## Priority Order

1. **CRITICAL** - Fix remaining SQL injections (items 3, 4, 5)
2. **CRITICAL** - Fix CORS configuration (item 6)
3. **HIGH** - Fix error message leaks (items 7, 8)
4. **MEDIUM** - Add rate limiting (item 9)
5. **MEDIUM** - Add input validation (item 10)
6. **MEDIUM** - Add security headers (item 11)

---

## Verification Checklist

After implementing fixes:

- [ ] All SQL queries use parameterized statements
- [ ] No string interpolation in SQL
- [ ] CORS restricted to known origins
- [ ] Error messages don't expose internal details
- [ ] Input validation on all endpoints
- [ ] Rate limiting implemented
- [ ] Security headers configured
- [ ] Timestamp window reduced to 2 minutes
- [ ] All tests pass
- [ ] Security scan shows no critical issues

---

## Additional Recommendations

1. **Enable SQL Query Logging** (development only):
   ```kotlin
   addLogger(StdOutSqlLogger)
   ```

2. **Use Environment Variables** for sensitive config:
   ```kotlin
   val allowedOrigin = System.getenv("ALLOWED_ORIGIN") ?: "http://localhost:3000"
   ```

3. **Implement Audit Logging**:
   ```kotlin
   fun logSecurityEvent(event: String, userId: String?, details: String) {
       val timestamp = Instant.now()
       println("SECURITY: $timestamp - $event - User: $userId - $details")
       // In production: send to SIEM or security logging service
   }
   ```

4. **Regular Security Updates**:
    - Keep dependencies updated
    - Monitor CVE databases
    - Run security scans regularly
    - Conduct penetration testing

---

**Status**: 40% Complete  
**Remaining Work**: ~2-4 hours to implement all fixes  
**Risk Level After Fixes**: LOW
