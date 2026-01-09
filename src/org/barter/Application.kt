package org.barter

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.barter.errors.GenericServerError
import org.barter.extensions.DatabaseFactory
import org.barter.features.ai.AttributeCategorizer
import org.barter.features.ai.data.ExpandedInterests
import org.barter.features.attributes.dao.AttributesDao
import org.barter.features.attributes.dao.AttributesDaoImpl
import org.barter.features.authentication.di.authenticationModule
import org.barter.features.categories.di.categoriesModule
import org.barter.features.chat.di.chatModule
import org.barter.features.healthcheck.di.healthCheckModule
import org.barter.features.postings.dao.UserPostingDao
import org.barter.features.postings.di.postingsModule
import org.barter.features.postings.tasks.PostingExpirationTask
import org.barter.features.profile.di.profilesModule
import org.barter.features.reviews.dao.RiskPatternDao
import org.barter.features.reviews.tasks.ReviewRiskTrackingCleanupTask
import org.barter.features.relationships.di.relationshipsModule
import org.barter.features.notifications.di.notificationsModule
import org.barter.features.reviews.di.reviewsModule
import org.barter.features.notifications.jobs.DigestNotificationJobManager
import org.barter.features.profile.cache.UserActivityCache
import org.barter.middleware.installActivityTracking
import org.barter.config.configureRateLimiting
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.koin.java.KoinJavaComponent.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.SLF4JLogger
import org.slf4j.event.Level
import java.security.Security
import java.text.DateFormat

fun main(args: Array<String>): Unit {

    embeddedServer(
        Netty,
        port = 8081, // or your desired main app port from application.conf
        host = "0.0.0.0", // 0.0.0.0 - Docker custom localhost bridge IP for development
        module = Application::module,
        //watchPaths = listOf("classesToWatch", "resourcesToWatch") // optional, for auto-reloading
    ).start(wait = true)
}

@OptIn(DelicateCoroutinesApi::class)
@Suppress("unused") // Referenced in application.conf
fun Application.module(testing: Boolean = false) {

    install(CORS) {
        anyHost()
        /*allowOrigins { it ->
            it.startsWith("http://localhost") || it.startsWith("http://127.0.0.1")
        }
         */
        // For production, you would be more specific:
        // allowHost("your-flutter-app-domain.com", schemes = listOf("https"))

        // --- Methods ---
        // You MUST allow the OPTIONS method for preflight requests.
        allowMethod(HttpMethod.Options)
        // Also allow all the other methods your API uses.
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)

        // --- Headers ---
        // You MUST explicitly allow the custom headers your Flutter app sends.
        allowHeader("X-User-ID")
        allowHeader("X-Timestamp")
        allowHeader("X-Signature")

        // Also allow common headers. `HttpHeaders.ContentType` is good practice.
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization) // Good to have if you add token auth later

        // --- Miscellaneous ---
        // Allow cookies or other credentials to be sent. Good practice to have.
        allowCredentials = true
        // Some browsers may require this header for credentials to work.
        allowHeader(HttpHeaders.AccessControlAllowCredentials)
    }

    Security.addProvider(BouncyCastleProvider())

    DatabaseFactory.init()
    
    // Initialize user activity cache for presence tracking
    UserActivityCache.init()
    println("✅ User activity tracking initialized")

    // Install rate limiting (application-level protection)
    configureRateLimiting()
    
    // Install activity tracking middleware (tracks user presence)
    installActivityTracking()

    install(WebSockets) {
        pingPeriodMillis = 15_000
        timeoutMillis = 15_000
        maxFrameSize = Long.MAX_VALUE
        masking = false
        contentConverter = KotlinxWebsocketSerializationConverter(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true // Important for handling different message types
        })
    }

    install(Koin) {
        SLF4JLogger()
        modules(
            authenticationModule,
            profilesModule,
            categoriesModule,
            chatModule,
            healthCheckModule,
            relationshipsModule,
            postingsModule,
            notificationsModule,
            reviewsModule
        )
    }

    val attributesDao: AttributesDao by inject<AttributesDaoImpl>(AttributesDaoImpl::class.java)
    runBlocking { // Use runBlocking for a one-time startup task
        AttributeCategorizer().initialize()
        ExpandedInterests.all.forEach {
            attributesDao.findOrCreate(it)
        }
        attributesDao.populateMissingEmbeddings()
        // Uncomment to run tests:
        //TestRandom100UsersGenAndSimilarity.execute()  // Random user generation test
        //TestArchetypeUsersGenAndSimilarity.execute()  // Archetype-based user generation and matching test
    }

    val postingDao: UserPostingDao by inject(UserPostingDao::class.java)
    val expirationTask = PostingExpirationTask(postingDao)
    expirationTask.start(GlobalScope)
    
    // Start risk tracking data cleanup task
    val riskPatternDao: RiskPatternDao by inject(RiskPatternDao::class.java)
    val riskCleanupTask = ReviewRiskTrackingCleanupTask(riskPatternDao)
    riskCleanupTask.start(GlobalScope)
    println("✅ Risk tracking cleanup task started")
    
    // Start digest notification jobs
    DigestNotificationJobManager.startJobs()
    println("✅ Digest notification jobs started")

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.response.status(HttpStatusCode.InternalServerError)
            call.respond(GenericServerError(500, cause.message.toString()))
            throw cause
        }
        exception<com.fasterxml.jackson.databind.exc.MismatchedInputException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest)
            throw cause
        }
    }

    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT)
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
            registerModule(JavaTimeModule())
            dateFormat = DateFormat.getDateInstance()
        }
        register(ContentType.Text.Plain, KotlinxSerializationConverter(Json))
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/") }
    }

    routes()

}