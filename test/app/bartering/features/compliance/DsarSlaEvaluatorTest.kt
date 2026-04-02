package app.bartering.features.compliance

import app.bartering.features.compliance.service.ComplianceAuditEventView
import app.bartering.features.compliance.service.DsarSlaEvaluator
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// DSAR = Data Subject Access Request.
// SLA = agreed maximum time to complete DSAR processing.
class DsarSlaEvaluatorTest {

    private fun event(
        id: Long,
        actorId: String,
        eventType: String,
        createdAt: Instant
    ): ComplianceAuditEventView = ComplianceAuditEventView(
        id = id,
        actorType = "user",
        actorId = actorId,
        eventType = eventType,
        entityType = "test",
        entityId = actorId,
        purpose = "test",
        outcome = "success",
        requestId = null,
        createdAt = createdAt,
        details = buildJsonObject { }
    )

    @Test
    fun `evaluate marks completed requests within SLA`() {
        val now = Instant.parse("2026-04-02T10:00:00Z")
        val requests = listOf(
            event(1, "user-a", "DATA_EXPORT_REQUESTED", now.minusSeconds(3 * 24 * 3600)),
            event(2, "user-b", "ACCOUNT_DELETION_REQUESTED", now.minusSeconds(2 * 24 * 3600))
        )
        val completions = listOf(
            event(3, "user-a", "DATA_EXPORT_COMPLETED", now.minusSeconds(2 * 24 * 3600)),
            event(4, "user-b", "ACCOUNT_DELETION_COMPLETED", now.minusSeconds(1 * 24 * 3600))
        )

        val result = DsarSlaEvaluator.evaluate(
            requests = requests,
            completions = completions,
            slaDays = 30,
            now = now
        )

        assertEquals(2, result.totalRequests)
        assertEquals(2, result.completedWithinSla)
        assertEquals(0, result.breached)
        assertEquals(0, result.open)
    }

    @Test
    fun `evaluate marks overdue open request as breached`() {
        val now = Instant.parse("2026-04-02T10:00:00Z")
        val requests = listOf(
            event(10, "user-c", "DATA_EXPORT_REQUESTED", now.minusSeconds(40L * 24 * 3600))
        )

        val result = DsarSlaEvaluator.evaluate(
            requests = requests,
            completions = emptyList(),
            slaDays = 30,
            now = now
        )

        assertEquals(1, result.totalRequests)
        assertEquals(0, result.completedWithinSla)
        assertEquals(1, result.breached)
        assertEquals(0, result.open)
        assertTrue(result.breachedActorIds.contains("user-c"))
    }
}
