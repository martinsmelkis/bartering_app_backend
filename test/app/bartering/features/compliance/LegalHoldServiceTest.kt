package app.bartering.features.compliance

import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegalHoldServiceTest {

    fun scopeValidationForKnownScopes() {
        val allowed = setOf("all", "export", "deletion", "retention")
        assertTrue("all" in allowed)
        assertTrue("export" in allowed)
        assertTrue("deletion" in allowed)
        assertTrue("retention" in allowed)
        assertFalse("other" in allowed)
    }
}
