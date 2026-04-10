package app.bartering

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
import io.ktor.server.application.ApplicationStopped
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import app.bartering.errors.GenericServerError
import app.bartering.extensions.DatabaseFactory
import app.bartering.features.ai.AttributeCategorizer
import app.bartering.features.ai.data.Attributes
import app.bartering.features.attributes.dao.AttributesDao
import app.bartering.features.attributes.dao.AttributesDaoImpl
import app.bartering.features.authentication.di.authenticationModule
import app.bartering.features.analytics.di.analyticsModule
import app.bartering.features.categories.di.categoriesModule
import app.bartering.features.chat.di.chatModule
import app.bartering.features.healthcheck.di.healthCheckModule
import app.bartering.features.migration.di.migrationModule
import app.bartering.features.postings.di.postingsModule
import app.bartering.features.profile.di.profilesModule
import app.bartering.features.relationships.di.relationshipsModule
import app.bartering.features.notifications.di.notificationsModule
import app.bartering.features.reviews.di.reviewsModule
import app.bartering.features.notifications.jobs.DigestNotificationJobManager
import app.bartering.features.profile.cache.UserActivityCache
import app.bartering.features.profile.tasks.InactiveUserCleanupTask
import app.bartering.features.notifications.service.EmailService
import app.bartering.features.notifications.service.NotificationOrchestrator
import app.bartering.middleware.installActivityTracking
import app.bartering.middleware.installComplianceAuditInterceptor
import app.bartering.config.configureRateLimiting
import app.bartering.features.federation.di.federationModule
import app.bartering.features.wallet.di.walletModule
import app.bartering.features.purchases.di.purchasesModule
import app.bartering.features.wallet.service.UserActivityRewardService
import app.bartering.features.wallet.tasks.UserActivityRewardTask
import app.bartering.features.compliance.di.complianceModule
import app.bartering.features.compliance.service.RetentionOrchestrator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.inject
import org.koin.ktor.plugin.Koin
import org.koin.logger.SLF4JLogger
import org.slf4j.event.Level
import org.slf4j.LoggerFactory
import java.security.Security
import java.text.DateFormat

private val log = LoggerFactory.getLogger("app.bartering.Application")

fun main() {

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

    // CORS Configuration
    // - Only needed for local development with different ports
    val isDevelopment = System.getenv("ENVIRONMENT")?.lowercase() == "development"
    
    if (isDevelopment) {
        install(CORS) {
            anyHost()
            // You MUST allow the OPTIONS method for preflight requests.
            allowMethod(HttpMethod.Options)
            // Also allow all the other methods your API uses.
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Delete)

            // You MUST explicitly allow the custom headers your Flutter app sends.
            allowHeader("X-User-ID")
            allowHeader("X-Timestamp")
            allowHeader("X-Signature")

            // Also allow common headers. `HttpHeaders.ContentType` is good practice.
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Accept) // Required for WebP content negotiation
            allowHeader(HttpHeaders.Authorization) // Good to have if you add token auth later

            // Allow cookies or other credentials to be sent. Good practice to have.
            allowCredentials = true
            // Some browsers may require this header for credentials to work.
            allowHeader(HttpHeaders.AccessControlAllowCredentials)
        }
        log.info("🔧 CORS enabled for development")
    } else {
        log.info("🔒 CORS disabled for production (same-domain nginx setup)")
    }

    Security.addProvider(BouncyCastleProvider())

    DatabaseFactory.init()
    
    // Initialize user activity cache for presence tracking
    UserActivityCache.init()
    log.info("✅ User activity tracking initialized")

    // Install rate limiting (application-level protection)
    configureRateLimiting()
    
    // Install activity tracking middleware (tracks user presence)
    installActivityTracking()

    // Install sensitive endpoint access audit middleware (GDPR accountability)
    installComplianceAuditInterceptor()

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
            analyticsModule,
            profilesModule,
            categoriesModule,
            chatModule,
            healthCheckModule,
            relationshipsModule,
            postingsModule,
            notificationsModule,
            reviewsModule,
            migrationModule,
            federationModule,
            walletModule,
            purchasesModule,
            complianceModule
        )
    }

    val authBackgroundScope: CoroutineScope by inject(
        CoroutineScope::class.java,
        qualifier = named("authBackgroundScope")
    )
    val appBackgroundScope: CoroutineScope by inject(
        CoroutineScope::class.java,
        qualifier = named("appBackgroundScope")
    )

    // Register shutdown hook to close resources
    monitor.subscribe(ApplicationStopped) {
        val emailService: EmailService by inject(EmailService::class.java)
        emailService.close()
        log.info("✅ Email service closed")

        authBackgroundScope.cancel("Application stopping")
        log.info("✅ Auth background scope cancelled")

        appBackgroundScope.cancel("Application stopping")
        log.info("✅ App background scope cancelled")
    }

    val attributesDao: AttributesDao by inject<AttributesDaoImpl>(AttributesDaoImpl::class.java)
    runBlocking { // Use runBlocking for a one-time startup task
        AttributeCategorizer().initialize()
        // Initialize all ExpandedInterests attributes as approved
        log.info("Initializing ${Attributes.all.size} approved attributes from ExpandedInterests...")
        Attributes.all.forEach {
            attributesDao.findOrCreate(it, isApproved = true)
        }
        log.info("✅ ExpandedInterests attributes initialized as approved")
        attributesDao.populateMissingEmbeddings()
        // Uncomment to run tests:
        //TestRandom100UsersGenAndSimilarity.execute()  // Random user generation test
        //TestArchetypeUsersGenAndSimilarity.execute()  // Archetype-based user generation and matching test
    }

    // Unified retention orchestrator (cross-domain cleanup + compliance audit events)
    val retentionOrchestrator: RetentionOrchestrator by inject(RetentionOrchestrator::class.java)
    retentionOrchestrator.start(appBackgroundScope)
    log.info("✅ Retention orchestrator started")
    
    // Start digest notification jobs
    DigestNotificationJobManager.startJobs()
    log.info("✅ Digest notification jobs started")

    // Start activity-count wallet reward task (10 coins for each 30 actions)
    val userActivityRewardService: UserActivityRewardService by inject(UserActivityRewardService::class.java)
    val userActivityRewardTask = UserActivityRewardTask(userActivityRewardService)
    userActivityRewardTask.start(appBackgroundScope)
    log.info("✅ User activity reward task started")
    
    // Start inactive user cleanup task
    val notificationOrchestrator: NotificationOrchestrator by inject(NotificationOrchestrator::class.java)
    val enableAutoDelete = System.getenv("INACTIVE_USER_AUTO_DELETE")?.toBoolean() ?: false
    val autoDeleteThreshold = System.getenv("INACTIVE_USER_AUTO_DELETE_THRESHOLD")?.toLong() ?: 180
    
    val inactiveUserCleanup = InactiveUserCleanupTask(
        notificationOrchestrator = notificationOrchestrator,
        enableAutoDelete = enableAutoDelete,
        autoDeleteThresholdDays = autoDeleteThreshold
    )
    inactiveUserCleanup.start()
    log.info("✅ Inactive user cleanup task started (auto-delete: $enableAutoDelete, threshold: $autoDeleteThreshold days)")

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