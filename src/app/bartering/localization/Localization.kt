package app.bartering.localization

import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap

object Localization {

    private const val BUNDLE_BASE_NAME = "locales.messages"
    private val bundles = ConcurrentHashMap<Locale, ResourceBundle>()

    private fun getBundle(locale: Locale): ResourceBundle {
        return bundles.computeIfAbsent(locale) {
            ResourceBundle.getBundle(BUNDLE_BASE_NAME, it, Utf8Control())
        }
    }

    fun getString(key: String, locale: Locale = Locale.ENGLISH, vararg args: Any): String {
        return try {
            String.format(getBundle(locale).getString(key), *args)
        } catch (_: Exception) {
            // Fallback to English if key not found in specified locale or other error
            try {
                String.format(getBundle(Locale.ENGLISH).getString(key), *args)
            } catch (_: Exception) {
                key // return key as last resort
            }
        }
    }

}