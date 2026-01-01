package org.barter.extensions

import java.text.Normalizer

fun String.normalizeAttributeForDBProcessing(): String {
    return Normalizer.normalize(this, Normalizer.Form.NFD)
        .lowercase()
        .replace(" ", "_")
        .replace("-", "_")
}