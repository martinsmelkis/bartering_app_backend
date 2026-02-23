package app.bartering.features.federation.dao

import app.bartering.extensions.DatabaseFactory.dbQuery
import app.bartering.features.federation.db.FederatedServersTable
import app.bartering.features.federation.db.FederationAuditLogTable
import app.bartering.features.federation.db.LocalServerIdentityTable
import app.bartering.features.federation.model.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import java.util.UUID

class FederationDaoImpl : FederationDao {

    override suspend fun getLocalServerIdentity(): LocalServerIdentity? = dbQuery {
        LocalServerIdentityTable.selectAll().limit(1).map { row ->
            LocalServerIdentity(
                serverId = row[LocalServerIdentityTable.serverId],
                serverUrl = row[LocalServerIdentityTable.serverUrl],
                serverName = row[LocalServerIdentityTable.serverName],
                publicKey = row[LocalServerIdentityTable.publicKey],
                privateKey = row[LocalServerIdentityTable.privateKey],
                keyAlgorithm = row[LocalServerIdentityTable.keyAlgorithm],
                keySize = row[LocalServerIdentityTable.keySize],
                protocolVersion = row[LocalServerIdentityTable.protocolVersion],
                adminContact = row[LocalServerIdentityTable.adminContact],
                description = row[LocalServerIdentityTable.description],
                locationHint = row[LocalServerIdentityTable.locationHint],
                keyGeneratedAt = row[LocalServerIdentityTable.keyGeneratedAt],
                keyRotationDue = row[LocalServerIdentityTable.keyRotationDue],
                createdAt = row[LocalServerIdentityTable.createdAt],
                updatedAt = row[LocalServerIdentityTable.updatedAt]
            )
        }.firstOrNull()
    }

    override suspend fun saveLocalServerIdentity(identity: LocalServerIdentity): LocalServerIdentity = dbQuery {
        LocalServerIdentityTable.insert {
            it[serverId] = identity.serverId
            it[serverUrl] = identity.serverUrl
            it[serverName] = identity.serverName
            it[publicKey] = identity.publicKey
            it[privateKey] = identity.privateKey
            it[keyAlgorithm] = identity.keyAlgorithm
            it[keySize] = identity.keySize
            it[protocolVersion] = identity.protocolVersion
            it[adminContact] = identity.adminContact
            it[description] = identity.description
            it[locationHint] = identity.locationHint
            it[keyGeneratedAt] = identity.keyGeneratedAt
            it[keyRotationDue] = identity.keyRotationDue
            it[createdAt] = identity.createdAt
            it[updatedAt] = identity.updatedAt
        }
        identity
    }

    override suspend fun updateLocalServerIdentity(identity: LocalServerIdentity): LocalServerIdentity = dbQuery {
        LocalServerIdentityTable.update({ LocalServerIdentityTable.serverId eq identity.serverId }) {
            it[serverUrl] = identity.serverUrl
            it[serverName] = identity.serverName
            it[publicKey] = identity.publicKey
            it[privateKey] = identity.privateKey
            it[keyAlgorithm] = identity.keyAlgorithm
            it[keySize] = identity.keySize
            it[protocolVersion] = identity.protocolVersion
            it[adminContact] = identity.adminContact
            it[description] = identity.description
            it[locationHint] = identity.locationHint
            it[keyRotationDue] = identity.keyRotationDue
            it[updatedAt] = Instant.now()
        }
        identity
    }

    override suspend fun getFederatedServer(serverId: String): FederatedServer? = dbQuery {
        FederatedServersTable.selectAll().where { FederatedServersTable.serverId eq serverId }.map { row ->
            rowToFederatedServer(row)
        }.firstOrNull()
    }

    override suspend fun listFederatedServers(trustLevel: TrustLevel?): List<FederatedServer> = dbQuery {
        val query = if (trustLevel != null) {
            FederatedServersTable.selectAll().where {
                FederatedServersTable.trustLevel eq trustLevel.name
            }
        } else {
            FederatedServersTable.selectAll()
        }
        query.map { rowToFederatedServer(it) }
    }

    override suspend fun createFederatedServer(server: FederatedServer): FederatedServer = dbQuery {
        FederatedServersTable.insert {
            it[serverId] = server.serverId
            it[serverUrl] = server.serverUrl
            it[serverName] = server.serverName
            it[publicKey] = server.publicKey
            it[trustLevel] = server.trustLevel.name
            it[scopePermissions] = mapOf(
                "users" to server.scopePermissions.users,
                "postings" to server.scopePermissions.postings,
                "chat" to server.scopePermissions.chat,
                "geolocation" to server.scopePermissions.geolocation,
                "attributes" to server.scopePermissions.attributes
            )
            it[federationAgreementHash] = server.federationAgreementHash
            it[lastSyncTimestamp] = server.lastSyncTimestamp
            it[serverMetadata] = server.serverMetadata
            it[protocolVersion] = server.protocolVersion
            it[isActive] = server.isActive
            it[dataRetentionDays] = server.dataRetentionDays
            it[createdAt] = server.createdAt
            it[updatedAt] = server.updatedAt
        }
        server
    }

    override suspend fun updateFederatedServer(serverId: String, updates: Map<String, Any?>): Boolean = dbQuery {
        val updatedCount = FederatedServersTable.update({ FederatedServersTable.serverId eq serverId }) {
            updates.forEach { (key, value) ->
                when (key) {
                    "serverName" -> it[serverName] = value as String?
                    "trustLevel" -> it[trustLevel] = (value as TrustLevel).name
                    "scopePermissions" -> {
                        val scope = value as FederationScope
                        it[scopePermissions] = mapOf(
                            "users" to scope.users,
                            "postings" to scope.postings,
                            "chat" to scope.chat,
                            "geolocation" to scope.geolocation,
                            "attributes" to scope.attributes
                        )
                    }
                    "federationAgreementHash" -> it[federationAgreementHash] = value as String?
                    "isActive" -> it[isActive] = value as Boolean
                    "serverMetadata" -> it[serverMetadata] = value as Map<String, String>?
                }
            }
            it[updatedAt] = Instant.now()
        }
        updatedCount > 0
    }

    override suspend fun deleteFederatedServer(serverId: String): Boolean = dbQuery {
        val deletedCount = FederatedServersTable.deleteWhere { FederatedServersTable.serverId eq serverId }
        deletedCount > 0
    }

    override suspend fun updateServerTrustLevel(serverId: String, trustLevel: TrustLevel): Boolean = dbQuery {
        val updatedCount = FederatedServersTable.update({ FederatedServersTable.serverId eq serverId }) {
            it[FederatedServersTable.trustLevel] = trustLevel.name
            it[updatedAt] = Instant.now()
        }
        updatedCount > 0
    }

    override suspend fun updateServerScopes(serverId: String, scopes: FederationScope): Boolean = dbQuery {
        val updatedCount = FederatedServersTable.update({ FederatedServersTable.serverId eq serverId }) {
            it[scopePermissions] = mapOf(
                "users" to scopes.users,
                "postings" to scopes.postings,
                "chat" to scopes.chat,
                "geolocation" to scopes.geolocation,
                "attributes" to scopes.attributes
            )
            it[updatedAt] = Instant.now()
        }
        updatedCount > 0
    }

    override suspend fun updateServerLastSync(serverId: String): Boolean = dbQuery {
        val updatedCount = FederatedServersTable.update({ FederatedServersTable.serverId eq serverId }) {
            it[lastSyncTimestamp] = Instant.now()
            it[updatedAt] = Instant.now()
        }
        updatedCount > 0
    }

    override suspend fun logFederationEvent(
        eventType: FederationEventType,
        serverId: String?,
        action: String,
        outcome: FederationOutcome,
        details: Map<String, Any?>?,
        errorMessage: String?,
        durationMs: Long?,
        remoteIp: String?
    ): FederationAuditLog = dbQuery {
        val id = UUID.randomUUID().toString()
        val detailsMap: Map<String, Any>? = details?.mapValues { it.value ?: "" }
        FederationAuditLogTable.insert {
            it[this.id] = id
            it[this.eventType] = eventType.name
            it[this.serverId] = serverId
            it[localUserId] = null
            it[remoteUserId] = null
            it[this.action] = action
            it[this.outcome] = outcome.name
            it[this.details] = detailsMap
            it[this.errorMessage] = errorMessage
            it[this.remoteIp] = remoteIp
            it[this.durationMs] = durationMs
        }
        FederationAuditLog(
            id = id,
            eventType = eventType,
            serverId = serverId,
            localUserId = null,
            remoteUserId = null,
            action = action,
            outcome = outcome,
            details = detailsMap,
            errorMessage = errorMessage,
            remoteIp = remoteIp,
            durationMs = durationMs,
            timestamp = Instant.now()
        )
    }

    override suspend fun getAuditLogs(
        serverId: String?,
        eventType: FederationEventType?,
        limit: Int
    ): List<FederationAuditLog> = dbQuery {
        return@dbQuery FederationAuditLogTable.selectAll()
            .apply {
                if (serverId != null) {
                    andWhere { FederationAuditLogTable.serverId eq serverId }
                }
                if (eventType != null) {
                    andWhere { FederationAuditLogTable.eventType eq eventType.name }
                }
            }
            .orderBy(FederationAuditLogTable.timestamp, SortOrder.DESC)
            .limit(limit)
            .map { row ->
                FederationAuditLog(
                    id = row[FederationAuditLogTable.id],
                    eventType = FederationEventType.valueOf(row[FederationAuditLogTable.eventType]),
                    serverId = row[FederationAuditLogTable.serverId],
                    localUserId = row[FederationAuditLogTable.localUserId],
                    remoteUserId = row[FederationAuditLogTable.remoteUserId],
                    action = row[FederationAuditLogTable.action],
                    outcome = FederationOutcome.valueOf(row[FederationAuditLogTable.outcome]),
                    details = row[FederationAuditLogTable.details],
                    errorMessage = row[FederationAuditLogTable.errorMessage],
                    remoteIp = row[FederationAuditLogTable.remoteIp],
                    durationMs = row[FederationAuditLogTable.durationMs],
                    timestamp = row[FederationAuditLogTable.timestamp]
                )
            }
    }

    private fun rowToFederatedServer(row: ResultRow): FederatedServer {
        val scopeMap = row[FederatedServersTable.scopePermissions]
        val scopePermissions = FederationScope(
            users = scopeMap["users"] as? Boolean ?: false,
            postings = scopeMap["postings"] as? Boolean ?: false,
            chat = scopeMap["chat"] as? Boolean ?: false,
            geolocation = scopeMap["geolocation"] as? Boolean ?: false,
            attributes = scopeMap["attributes"] as? Boolean ?: false
        )

        return FederatedServer(
            serverId = row[FederatedServersTable.serverId],
            serverUrl = row[FederatedServersTable.serverUrl],
            serverName = row[FederatedServersTable.serverName],
            publicKey = row[FederatedServersTable.publicKey],
            trustLevel = TrustLevel.valueOf(row[FederatedServersTable.trustLevel]),
            scopePermissions = scopePermissions,
            federationAgreementHash = row[FederatedServersTable.federationAgreementHash],
            lastSyncTimestamp = row[FederatedServersTable.lastSyncTimestamp],
            serverMetadata = row[FederatedServersTable.serverMetadata],
            protocolVersion = row[FederatedServersTable.protocolVersion],
            isActive = row[FederatedServersTable.isActive],
            dataRetentionDays = row[FederatedServersTable.dataRetentionDays],
            createdAt = row[FederatedServersTable.createdAt],
            updatedAt = row[FederatedServersTable.updatedAt]
        )
    }
}