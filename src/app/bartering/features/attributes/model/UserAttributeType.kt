package app.bartering.features.attributes.model

/**
 * Defines the user's relationship with an attribute.
 * - SEEKING: The user is looking for this (an Interest).
 * - PROVIDING: The user can provide this (an Offer).
 * - SHARING: Both seeking and providing (e.g., finding a partner for a hobby). LEARNING applies too?
 * - PROFILE: self-assessment, from onboarding and profile editing
 */
enum class UserAttributeType { SEEKING, PROVIDING, SHARING, PROFILE }