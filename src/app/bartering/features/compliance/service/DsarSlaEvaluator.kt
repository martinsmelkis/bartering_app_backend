package app.bartering.features.compliance.service

import java.time.Duration
import java.time.Instant

object DsarSlaEvaluator {
    // DSAR = Data Subject Access Request.
    // SLA = agreed maximum time to complete DSAR processing.
    fun evaluate(
        requests: List<ComplianceAuditEventView>,
        completions: List<ComplianceAuditEventView>,
        slaDays: Int,
        now: Instant = Instant.now()
    ): DsarSlaEvaluationResult {
        val completionByActor = completions
            .filter { !it.actorId.isNullOrBlank() }
            .groupBy { it.actorId!! }
            .mapValues { (_, events) -> events.sortedBy { it.createdAt }.toMutableList() }

        val sortedRequests = requests
            .filter { !it.actorId.isNullOrBlank() }
            .sortedBy { it.createdAt }

        var completedWithinSla = 0
        var breached = 0
        var open = 0
        val breachedActorIds = linkedSetOf<String>()

        for (request in sortedRequests) {
            val actorId = request.actorId!!
            val actorCompletions = completionByActor[actorId]
            var matchedCompletion: ComplianceAuditEventView? = null

            if (actorCompletions != null) {
                while (actorCompletions.isNotEmpty()) {
                    val candidate = actorCompletions.removeAt(0)
                    if (!candidate.createdAt.isBefore(request.createdAt)) {
                        matchedCompletion = candidate
                        break
                    }
                }
            }

            if (matchedCompletion == null) {
                val ageDays = Duration.between(request.createdAt, now).toDays()
                if (ageDays > slaDays) {
                    breached++
                    breachedActorIds.add(actorId)
                } else {
                    open++
                }
                continue
            }

            val durationDays = Duration.between(request.createdAt, matchedCompletion.createdAt).toDays()
            if (durationDays <= slaDays) {
                completedWithinSla++
            } else {
                breached++
                breachedActorIds.add(actorId)
            }
        }

        return DsarSlaEvaluationResult(
            totalRequests = sortedRequests.size,
            completedWithinSla = completedWithinSla,
            breached = breached,
            open = open,
            breachedActorIds = breachedActorIds.toList()
        )
    }
}

data class DsarSlaEvaluationResult(
    val totalRequests: Int,
    val completedWithinSla: Int,
    val breached: Int,
    val open: Int,
    val breachedActorIds: List<String>
)
