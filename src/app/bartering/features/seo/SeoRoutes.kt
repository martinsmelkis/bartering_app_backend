package app.bartering.features.seo

import app.bartering.extensions.DatabaseFactory.dbQuery
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val DEFAULT_PUBLIC_URL = "https://bartering.app"
private const val SITEMAP_CACHE_CONTROL = "public, max-age=3600"
private const val MAX_DYNAMIC_SITEMAP_URLS = 45_000

private data class SitemapUrl(
    val path: String,
    val lastModified: Instant? = null,
    val changeFrequency: String? = null,
    val priority: String? = null
)

fun Route.seoRoutes() {
    get("/robots.txt") {
        call.applySeoCacheHeaders()
        call.respondText(buildRobotsTxt(), ContentType.Text.Plain)
    }

    get("/sitemap.xml") {
        call.applySeoCacheHeaders()
        call.respondText(buildSitemapXml(), ContentType.parse("application/xml; charset=utf-8"))
    }
}

private fun ApplicationCall.applySeoCacheHeaders() {
    response.header(HttpHeaders.CacheControl, SITEMAP_CACHE_CONTROL)
}

private fun publicBaseUrl(): String =
    (System.getenv("PUBLIC_URL") ?: DEFAULT_PUBLIC_URL).trim().trimEnd('/').ifBlank { DEFAULT_PUBLIC_URL }

private fun buildRobotsTxt(): String {
    val baseUrl = publicBaseUrl()
    return """
        User-agent: *
        Allow: /
        Sitemap: $baseUrl/sitemap.xml

        Disallow: /api/
        Disallow: /admin/
        Disallow: /dashboard
        Disallow: /stats
        Disallow: /login
        Disallow: /logout
    """.trimIndent() + "\n"
}

private suspend fun buildSitemapXml(): String {
    val baseUrl = publicBaseUrl()
    val urls = buildList {
        add(SitemapUrl(path = "/", changeFrequency = "daily", priority = "1.0"))
        add(SitemapUrl(path = "/postings", changeFrequency = "hourly", priority = "0.9"))
        add(SitemapUrl(path = "/profiles", changeFrequency = "daily", priority = "0.8"))
        add(SitemapUrl(path = "/categories", changeFrequency = "weekly", priority = "0.7"))
        addAll(fetchDynamicSitemapUrls())
    }.distinctBy { it.path }.take(MAX_DYNAMIC_SITEMAP_URLS)

    return buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">")
        urls.forEach { sitemapUrl ->
            appendLine("  <url>")
            appendLine("    <loc>${xmlEscape(baseUrl + sitemapUrl.path)}</loc>")
            sitemapUrl.lastModified?.let {
                appendLine("    <lastmod>${DateTimeFormatter.ISO_LOCAL_DATE.format(it.atZone(ZoneOffset.UTC))}</lastmod>")
            }
            sitemapUrl.changeFrequency?.let {
                appendLine("    <changefreq>${xmlEscape(it)}</changefreq>")
            }
            sitemapUrl.priority?.let {
                appendLine("    <priority>${xmlEscape(it)}</priority>")
            }
            appendLine("  </url>")
        }
        appendLine("</urlset>")
    }
}

private suspend fun fetchDynamicSitemapUrls(): List<SitemapUrl> = dbQuery {
    val urls = mutableListOf<SitemapUrl>()

    TransactionManager.current().exec(
        """
            SELECT id, updated_at
            FROM user_postings
            WHERE status = 'active'
                AND (expires_at IS NULL OR expires_at > NOW())
            ORDER BY updated_at DESC
            LIMIT ?
        """.trimIndent(),
        listOf(org.jetbrains.exposed.v1.core.IntegerColumnType() to MAX_DYNAMIC_SITEMAP_URLS)
    ) { rs ->
        while (rs.next()) {
            urls.add(
                SitemapUrl(
                    path = "/postings/${urlPathSegment(rs.getString("id"))}",
                    lastModified = rs.getTimestamp("updated_at")?.toInstant(),
                    changeFrequency = "weekly",
                    priority = "0.8"
                )
            )
        }
    }

    val remainingAfterPostings = (MAX_DYNAMIC_SITEMAP_URLS - urls.size).coerceAtLeast(0)
    if (remainingAfterPostings > 0) {
        TransactionManager.current().exec(
            """
                SELECT user_id, updated_at
                FROM user_profiles
                ORDER BY updated_at DESC
                LIMIT ?
            """.trimIndent(),
            listOf(org.jetbrains.exposed.v1.core.IntegerColumnType() to remainingAfterPostings)
        ) { rs ->
            while (rs.next()) {
                urls.add(
                    SitemapUrl(
                        path = "/profiles/${urlPathSegment(rs.getString("user_id"))}",
                        lastModified = rs.getTimestamp("updated_at")?.toInstant(),
                        changeFrequency = "weekly",
                        priority = "0.7"
                    )
                )
            }
        }
    }

    val remainingAfterProfiles = (MAX_DYNAMIC_SITEMAP_URLS - urls.size).coerceAtLeast(0)
    if (remainingAfterProfiles > 0) {
        TransactionManager.current().exec(
            """
                SELECT category_key
                FROM categories
                ORDER BY category_key ASC
                LIMIT ?
            """.trimIndent(),
            listOf(org.jetbrains.exposed.v1.core.IntegerColumnType() to remainingAfterProfiles)
        ) { rs ->
            while (rs.next()) {
                urls.add(
                    SitemapUrl(
                        path = "/categories/${urlPathSegment(rs.getString("category_key"))}",
                        changeFrequency = "monthly",
                        priority = "0.6"
                    )
                )
            }
        }
    }

    urls
}

private fun urlPathSegment(value: String): String =
    value.trim().replace(Regex("[^A-Za-z0-9._~-]"), "-")

private fun xmlEscape(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
