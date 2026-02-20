package app.bartering.model

/**
 * Simple data class to hold 4 values (since Kotlin doesn't have built-in Quadruple)
 */
data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)