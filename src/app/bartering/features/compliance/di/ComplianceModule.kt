package app.bartering.features.compliance.di

import app.bartering.features.chat.dao.ChatAnalyticsDao
import app.bartering.features.chat.dao.ChatAnalyticsDaoImpl
import app.bartering.features.chat.dao.OfflineMessageDao
import app.bartering.features.chat.dao.OfflineMessageDaoImpl
import app.bartering.features.chat.dao.ReadReceiptDao
import app.bartering.features.chat.dao.ReadReceiptDaoImpl
import app.bartering.features.compliance.service.ComplianceAuditService
import app.bartering.features.compliance.service.LegalHoldService
import app.bartering.features.compliance.service.RetentionOrchestrator
import app.bartering.features.encryptedfiles.dao.EncryptedFileDao
import app.bartering.features.encryptedfiles.dao.EncryptedFileDaoImpl
import app.bartering.features.postings.dao.UserPostingDao
import org.koin.dsl.module

val complianceModule = module {
    // Ensure cleanup DAOs are available for orchestrator, without changing existing call sites.
    single<OfflineMessageDao> { OfflineMessageDaoImpl() }
    single<ReadReceiptDao> { ReadReceiptDaoImpl() }
    single<EncryptedFileDao> { EncryptedFileDaoImpl() }
    single<ChatAnalyticsDao> { ChatAnalyticsDaoImpl() }

    single { ComplianceAuditService() }
    single { LegalHoldService() }

    single {
        RetentionOrchestrator(
            offlineMessageDao = get(),
            chatAnalyticsDao = get(),
            readReceiptDao = get(),
            encryptedFileDao = get(),
            migrationDao = get(),
            userPostingDao = get<UserPostingDao>(),
            riskPatternDao = get(),
            legalHoldService = get(),
            complianceAuditService = get()
        )
    }
}
