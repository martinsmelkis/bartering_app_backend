package app.bartering.features.purchases.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object RevenueCatProcessedEventsTable : Table("revenuecat_processed_events") {
    val id = varchar("id", 36)
    val eventId = varchar("event_id", 128).uniqueIndex()
    val appUserId = varchar("app_user_id", 128).nullable()
    val eventType = varchar("event_type", 64).nullable()
    val eventAt = timestamp("event_at").nullable()
    val processedAt = timestamp("processed_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}
