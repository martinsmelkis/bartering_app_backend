package app.bartering.dashboard_admin_compliance

import app.bartering.dashboard_admin_compliance.features.network.BackendAdminApiClient
import app.bartering.dashboard_admin_compliance.models.auth.DashboardConfig
import app.bartering.dashboard_admin_compliance.models.auth.DashboardSession
import app.bartering.dashboard_admin_compliance.models.auth.DashboardSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.session
import io.ktor.server.auth.Authentication
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.*
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.security.MessageDigest
import java.util.Base64

private val logger = LoggerFactory.getLogger("DashboardApplication")

fun main() {
    embeddedServer(
        Netty,
        host = "0.0.0.0",
        port = 8094,
        module = Application::module
    ).start(wait = true)
}

fun Application.module() {
    val appConfig = environment.config

    val dashboardConfig = DashboardConfig(
        backendBaseUrl = System.getenv("DASHBOARD_BACKEND_BASE_URL")
            ?: appConfig.propertyOrNull("dashboard.backendBaseUrl")?.getString()
            ?: "http://localhost:8081",
        adminCredentials = System.getenv("DASHBOARD_ADMIN_CREDENTIALS")
            ?: appConfig.propertyOrNull("dashboard.adminCredentials")?.getString()
            ?: "",
        secureCookies = System.getenv("DASHBOARD_SECURE_COOKIES")?.toBooleanStrictOrNull()
            ?: appConfig.propertyOrNull("dashboard.secureCookies")?.getString()?.toBooleanStrictOrNull()
            ?: false,
        adminUserId = System.getenv("DASHBOARD_ADMIN_USER_ID")
            ?: appConfig.propertyOrNull("dashboard.adminUserId")?.getString()
            ?: "",
        adminPrivateKeyHex = System.getenv("DASHBOARD_ADMIN_PRIVATE_KEY_HEX")
            ?: appConfig.propertyOrNull("dashboard.adminPrivateKeyHex")?.getString()
            ?: "",
        sessionEncryptionKeyB64 = System.getenv("DASHBOARD_SESSION_ENCRYPTION_KEY_B64")
            ?: appConfig.propertyOrNull("dashboard.sessionEncryptionKeyB64")?.getString()
            ?: "",
        sessionSigningKeyB64 = System.getenv("DASHBOARD_SESSION_SIGNING_KEY_B64")
            ?: appConfig.propertyOrNull("dashboard.sessionSigningKeyB64")?.getString()
            ?: "",
        sessionTtlSeconds = System.getenv("DASHBOARD_SESSION_TTL_SECONDS")?.toLongOrNull()
            ?: appConfig.propertyOrNull("dashboard.sessionTtlSeconds")?.getString()?.toLongOrNull()
            ?: 3600L
    )

    val client = HttpClient(CIO) {
        install(ClientContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(Logging) {
            level = LogLevel.INFO
        }
    }

    val sessionEncryptKey = base64KeyOrNull(dashboardConfig.sessionEncryptionKeyB64, setOf(16))
    val sessionSignKey = base64KeyOrNull(dashboardConfig.sessionSigningKeyB64, setOf(32))

    val parsedHashedAdmins = parseAdminCredentials(dashboardConfig.adminCredentials)

    val backendApi = BackendAdminApiClient(dashboardConfig, client)

    if (sessionEncryptKey == null || sessionSignKey == null) {
        logger.warn("Dashboard session encryption/signing keys are not configured; login will be blocked until DASHBOARD_SESSION_ENCRYPTION_KEY_B64 and DASHBOARD_SESSION_SIGNING_KEY_B64 are set.")
    }
    if (parsedHashedAdmins.isEmpty()) {
        logger.warn("No dashboard admin credentials configured. Set DASHBOARD_ADMIN_CREDENTIALS.")
    }

    install(CallLogging) {
        level = Level.INFO
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(
                "Internal dashboard error: ${cause.message}",
                status = HttpStatusCode.InternalServerError
            )
        }
    }

    install(Sessions) {
        cookie<DashboardSession>("dashboard_session") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = dashboardConfig.secureCookies
            cookie.extensions["SameSite"] = "strict"
            if (sessionEncryptKey != null && sessionSignKey != null) {
                transform(SessionTransportTransformerEncrypt(sessionEncryptKey, sessionSignKey))
            }
        }
    }

    install(Authentication) {
        session<DashboardSession>("dashboard-auth") {
            validate { session ->
                val now = System.currentTimeMillis() / 1000
                val keysConfigured = sessionEncryptKey != null && sessionSignKey != null
                if (
                    keysConfigured &&
                    session.username.isNotBlank() &&
                    session.issuedAtEpochSeconds in 1..now &&
                    session.expiresAtEpochSeconds > now &&
                    session.expiresAtEpochSeconds - session.issuedAtEpochSeconds <= dashboardConfig.sessionTtlSeconds
                ) {
                    session
                } else {
                    null
                }
            }
            challenge {
                call.respondRedirect("/login")
            }
        }
    }

    routing {
        get("/") {
            val session = call.sessions.get<DashboardSession>()
            if (session == null) {
                call.respondRedirect("/login")
            } else {
                call.respondRedirect("/dashboard")
            }
        }

        get("/login") {
            call.respondText(buildLoginPage(), ContentType.Text.Html)
        }

        post("/login") {
            val params = call.receiveParameters()
            val username = params["username"]?.trim().orEmpty()
            val password = params["password"].orEmpty()

            val keysConfigured = sessionEncryptKey != null && sessionSignKey != null
            if (!keysConfigured) {
                call.respondText(
                    buildLoginPage(error = "Dashboard session security keys are missing. Set DASHBOARD_SESSION_ENCRYPTION_KEY_B64 and DASHBOARD_SESSION_SIGNING_KEY_B64."),
                    ContentType.Text.Html
                )
                return@post
            }

            val isValidLogin = isValidAdminLogin(
                username = username,
                password = password,
                hashedAdmins = parsedHashedAdmins
            )

            if (isValidLogin) {
                val now = System.currentTimeMillis() / 1000
                val ttl = dashboardConfig.sessionTtlSeconds.coerceAtLeast(60L)
                call.sessions.set(
                    DashboardSession(
                        username = username,
                        issuedAtEpochSeconds = now,
                        expiresAtEpochSeconds = now + ttl
                    )
                )
                call.respondRedirect("/dashboard")
            } else {
                call.respondText(buildLoginPage(error = "Invalid credentials"), ContentType.Text.Html)
            }
        }

        post("/logout") {
            call.sessions.clear<DashboardSession>()
            call.respondRedirect("/login")
        }

        authenticate("dashboard-auth") {
            get("/dashboard") {
                val snapshot = backendApi.fetchSnapshot()
                call.respondText(buildDashboardPage(snapshot), ContentType.Text.Html)
            }

            get("/partials/summary") {
                val snapshot = backendApi.fetchSnapshot()
                call.respondText(buildSummaryFragment(snapshot), ContentType.Text.Html)
            }
        }
    }
}

private fun buildLoginPage(error: String? = null): String = createHTML().html {
    head {
        title("Barter Admin Dashboard Login")
        meta(charset = "UTF-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        script(src = "https://cdn.tailwindcss.com") {}
    }
    body(classes = "bg-slate-100 min-h-screen flex items-center justify-center p-6") {
        div(classes = "w-full max-w-md bg-white shadow-xl rounded-2xl p-8") {
            h1(classes = "text-2xl font-bold text-slate-900 mb-2") { +"Admin Dashboard" }
            p(classes = "text-slate-600 mb-6") { +"Sign in to access internal metrics." }

            if (!error.isNullOrBlank()) {
                div(classes = "mb-4 rounded-lg bg-red-100 text-red-700 px-4 py-3 text-sm") {
                    +error
                }
            }

            form(action = "/login", method = FormMethod.post) {
                div(classes = "mb-4") {
                    label(classes = "block text-sm font-medium text-slate-700 mb-1") { +"Username" }
                    textInput(name = "username", classes = "w-full rounded-lg border border-slate-300 px-3 py-2") {
                        required = true
                    }
                }
                div(classes = "mb-6") {
                    label(classes = "block text-sm font-medium text-slate-700 mb-1") { +"Password" }
                    passwordInput(name = "password", classes = "w-full rounded-lg border border-slate-300 px-3 py-2") {
                        required = true
                    }
                }
                button(classes = "w-full rounded-lg bg-indigo-600 text-white py-2.5 font-semibold") {
                    +"Sign in"
                }
            }
        }
    }
}

private fun buildDashboardPage(snapshot: DashboardSnapshot): String = createHTML().html {
    head {
        title("Barter Admin Dashboard")
        meta(charset = "UTF-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        script(src = "https://cdn.tailwindcss.com") {}
        script(src = "https://unpkg.com/htmx.org@2.0.4") {}
    }
    body(classes = "bg-slate-100 min-h-screen") {
        header(classes = "bg-white border-b border-slate-200") {
            div(classes = "max-w-7xl mx-auto px-6 py-4 flex items-center justify-between") {
                h1(classes = "text-xl font-bold text-slate-900") { +"Barter Internal Admin Dashboard" }
                form(action = "/logout", method = FormMethod.post) {
                    button(classes = "rounded-lg border border-slate-300 px-4 py-2 text-sm") { +"Logout" }
                }
            }
        }

        main(classes = "max-w-7xl mx-auto px-6 py-6") {
            div {
                attributes["hx-get"] = "/partials/summary"
                attributes["hx-trigger"] = "load, every 30s"
                attributes["hx-swap"] = "innerHTML"
                unsafe {
                    +buildSummaryFragment(snapshot)
                }
            }
        }
    }
}

private fun buildSummaryFragment(snapshot: DashboardSnapshot): String = createHTML().div {
    div(classes = "grid gap-4 md:grid-cols-3 mb-4") {
        metricCard(
            title = "Backend Status",
            value = snapshot.backendStatus,
            tone = if (snapshot.backendStatus == "healthy") "green" else "amber"
        )
        metricCard(
            title = "Admin Data Link",
            value = if (snapshot.connected) "Connected" else "Disconnected",
            tone = if (snapshot.connected) "green" else "red"
        )
        metricCard(
            title = "Generated At",
            value = snapshot.complianceSummary?.generatedAt ?: "N/A",
            tone = "slate"
        )
    }

    val summary = snapshot.complianceSummary
    div(classes = "grid gap-4 md:grid-cols-2 lg:grid-cols-4") {
        metricCard("DSAR Total", summary?.dsarTotalRequests?.toString() ?: "-")
        metricCard("DSAR Breached", summary?.dsarBreached?.toString() ?: "-")
        metricCard("Data Exports", summary?.dataExportCompletedEvents?.toString() ?: "-")
        metricCard("Deletion Completed", summary?.accountDeletionCompletedEvents?.toString() ?: "-")
        metricCard("Legal Holds Applied", summary?.legalHoldAppliedEvents?.toString() ?: "-")
        metricCard("Legal Holds Released", summary?.legalHoldReleasedEvents?.toString() ?: "-")
        metricCard("Retention Tasks", summary?.retentionTaskCompletedEvents?.toString() ?: "-")
        metricCard("Retention Cycles", summary?.retentionCycleCompletedEvents?.toString() ?: "-")
    }

    if (!snapshot.connectionError.isNullOrBlank()) {
        div(classes = "mt-4 rounded-lg bg-amber-100 px-4 py-3 text-sm text-amber-800") {
            +"Admin API call warning: ${snapshot.connectionError}"
        }
    }
}

private fun FlowContent.metricCard(
    title: String,
    value: String,
    tone: String = "slate"
) {
    val toneClasses = when (tone) {
        "green" -> "bg-green-50 border-green-200"
        "red" -> "bg-red-50 border-red-200"
        "amber" -> "bg-amber-50 border-amber-200"
        else -> "bg-white border-slate-200"
    }

    div(classes = "rounded-xl border $toneClasses p-4") {
        p(classes = "text-xs uppercase tracking-wide text-slate-500") { +title }
        p(classes = "text-2xl font-semibold text-slate-900 mt-1") { +value }
    }
}

private fun base64KeyOrNull(raw: String, allowedSizes: Set<Int>): ByteArray? {
    if (raw.isBlank()) return null
    return runCatching { Base64.getDecoder().decode(raw.trim()) }
        .getOrNull()
        ?.takeIf { it.size in allowedSizes }
}

private fun parseAdminCredentials(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()

    return raw
        .split(";")
        .mapNotNull { entry ->
            val trimmed = entry.trim()
            if (trimmed.isBlank()) return@mapNotNull null

            val firstColon = trimmed.indexOf(':')
            if (firstColon <= 0 || firstColon >= trimmed.lastIndex) return@mapNotNull null

            val username = trimmed.substring(0, firstColon).trim()
            val hash = trimmed.substring(firstColon + 1).trim()
            if (username.isBlank() || hash.isBlank()) return@mapNotNull null

            username to hash
        }
        .toMap()
}

private fun isValidAdminLogin(
    username: String,
    password: String,
    hashedAdmins: Map<String, String>
): Boolean {
    val hashedPassword = hashedAdmins[username]
    if (hashedPassword.isNullOrBlank()) {
        return false
    }

    return verifyPasswordHash(password, hashedPassword)
}

private fun verifyPasswordHash(password: String, storedHash: String): Boolean {
    if (!storedHash.startsWith("sha256:")) {
        return false
    }

    val expected = storedHash.removePrefix("sha256:")
    val actual = sha256Hex(password)
    return constantTimeEquals(actual, expected)
}

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private fun constantTimeEquals(a: String, b: String): Boolean {
    val aBytes = a.toByteArray(Charsets.UTF_8)
    val bBytes = b.toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(aBytes, bBytes)
}
