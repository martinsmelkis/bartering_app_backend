package app.bartering.dashboard_admin_compliance.models.auth

import app.bartering.dashboard_admin_compliance.models.compliance.ComplianceSummaryResponse

data class DashboardSnapshot(
    val connected: Boolean,
    val backendStatus: String,
    val complianceSummary: ComplianceSummaryResponse?,
    val connectionError: String?
)