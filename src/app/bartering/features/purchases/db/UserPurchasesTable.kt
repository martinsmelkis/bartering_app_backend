package app.bartering.features.purchases.db

import app.bartering.features.profile.db.UserRegistrationDataTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

object UserPurchasesTable : Table("user_purchases") {
    val id = varchar("id", 36)
    val userId = reference("user_id", UserRegistrationDataTable.id, onDelete = ReferenceOption.CASCADE).index()
    val purchaseType = varchar("purchase_type", 50).index()
    val status = varchar("status", 32).index()
    val currency = varchar("currency", 10).nullable()
    val fiatAmountMinor = long("fiat_amount_minor").nullable()
    val coinAmount = long("coin_amount").nullable()
    val externalRef = varchar("external_ref", 255).nullable().index()
    val metadataJson = text("metadata_json").nullable()
    val fulfillmentRef = varchar("fulfillment_ref", 255).nullable()
    val createdAt = timestamp("created_at").default(Instant.now()).index()
    val updatedAt = timestamp("updated_at").default(Instant.now()).index()

    override val primaryKey = PrimaryKey(id)
}
