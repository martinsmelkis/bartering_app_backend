package app.bartering.features.purchases.model

enum class PurchaseType(val value: String) {
    PREMIUM_LIFETIME("premium_lifetime"),
    COIN_PACK("coin_pack"),
    VISIBILITY_BOOST("visibility_boost"),
    AVATAR_ICON_UNLOCK("avatar_icon_unlock");

    companion object {
        fun fromString(value: String): PurchaseType? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}
