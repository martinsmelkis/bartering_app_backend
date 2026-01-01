package org.barter.localization

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.PropertyResourceBundle
import java.util.ResourceBundle

// Helps to load properties files with UTF-8 encoding
class Utf8Control : ResourceBundle.Control() {
    @Throws(IllegalAccessException::class, InstantiationException::class, IOException::class)
    override fun newBundle(
        baseName: String, locale: Locale, format: String, loader: ClassLoader, reload: Boolean
    ): ResourceBundle? {
        val bundleName = toBundleName(baseName, locale)
        val resourceName = toResourceName(bundleName, "properties")
        var bundle: ResourceBundle? = null
        var stream: InputStream? = null
        if (reload) {
            val url = loader.getResource(resourceName)
            if (url != null) {
                val connection: URLConnection = url.openConnection()
                connection.useCaches = false
                stream = connection.getInputStream()
            }
        } else {
            stream = loader.getResourceAsStream(resourceName)
        }
        if (stream != null) {
            try {
                bundle = PropertyResourceBundle(InputStreamReader(stream, StandardCharsets.UTF_8))
            } finally {
                stream.close()
            }
        }
        return bundle
    }

}