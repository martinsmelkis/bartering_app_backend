package org.barter.features.notifications.di

import org.barter.features.notifications.dao.NotificationPreferencesDao
import org.barter.features.notifications.dao.NotificationPreferencesDaoImpl
import org.barter.features.notifications.service.*
import org.barter.features.notifications.service.impl.*
import org.koin.dsl.module

/**
 * Koin dependency injection module for notifications
 * 
 * Configuration options via environment variables:
 * - EMAIL_PROVIDER: sendgrid | aws_ses | smtp (default: sendgrid)
 * - PUSH_PROVIDER: firebase | aws_sns (default: firebase)
 * - SENDGRID_API_KEY: SendGrid API key
 * - AWS_REGION: AWS region for SES
 */
val notificationsModule = module {
    
    // DAO Implementation
    single<NotificationPreferencesDao> { 
        NotificationPreferencesDaoImpl() 
    }

    // Email Service - Choose implementation based on configuration
    single<EmailService> {
        val provider = System.getenv("EMAIL_PROVIDER") ?: "aws_ses"
        when (provider.lowercase()) {
            /*"sendgrid" -> SendGridEmailService(
                apiKey = System.getenv("SENDGRID_API_KEY")
                    ?: throw IllegalStateException("SENDGRID_API_KEY not set")
            )*/
            "aws_ses" -> AwsSesEmailService(
                region = System.getenv("AWS_REGION") ?: "us-east-1"
            )
            else -> throw IllegalArgumentException("Unsupported email provider: $provider")
        }
    }
    
    // Push Notification Service - Choose implementation based on configuration
    single<PushNotificationService> {
        val provider = System.getenv("PUSH_PROVIDER") ?: "firebase"
        when (provider.lowercase()) {
            "firebase" -> FirebasePushService()
            else -> throw IllegalArgumentException("Unsupported push provider: $provider")
        }
    }

    // Notification Orchestrator - Coordinates email and push services
    single {
        NotificationOrchestrator(
            emailService = get(),
            pushService = get(),
            preferencesDao = get()
        )
    }

    // Match Notification Service (new system)
    single {
        MatchNotificationService(
            preferencesDao = get(),
            postingDao = get(),
            orchestrator = get()
        )
    }

}
