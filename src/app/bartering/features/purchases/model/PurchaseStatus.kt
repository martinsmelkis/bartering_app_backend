package app.bartering.features.purchases.model

enum class PurchaseStatus(val value: String) {
    PENDING("pending"),
    COMPLETED("completed"),
    FAILED("failed"),
    REFUNDED("refunded");

    companion object {
        fun fromString(value: String): PurchaseStatus? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}
