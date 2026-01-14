package app.bartering.features.federation.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant

/**
 * Stores the identity and cryptographic keys for THIS server instance.
 * This is a singleton table (should only have one row) that defines how this server
 * identifies itself to other federated servers.
 */
object LocalServerIdentityTable : Table("local_server_identity") {
    
    /** Unique identifier for this server (UUID format) */
    val serverId = varchar("server_id", 36)
    
    /** Public URL where this server is accessible (e.g., "https://mybarter.example.com") */
    val serverUrl = varchar("server_url", 255)
    
    /** Display name for this server instance */
    val serverName = varchar("server_name", 255)
    
    /** Public key for this server (PEM format) - shared with other servers */
    val publicKey = text("public_key")
    
    /** Private key for this server (PEM format, encrypted at rest) - NEVER shared */
    val privateKey = text("private_key")
    
    /** Algorithm used for key generation (e.g., "RSA", "Ed25519") */
    val keyAlgorithm = varchar("key_algorithm", 20).default("RSA")
    
    /** Key size in bits (e.g., 2048, 4096 for RSA) */
    val keySize = integer("key_size").default(2048)
    
    /** Protocol version this server implements */
    val protocolVersion = varchar("protocol_version", 10).default("1.0")
    
    /** Admin contact email for federation issues */
    val adminContact = varchar("admin_contact", 255).nullable()
    
    /** Server description/bio */
    val description = text("description").nullable()
    
    /** Geographic location hint (city/region, not precise coordinates) */
    val locationHint = varchar("location_hint", 255).nullable()
    
    /** When these keys were generated */
    val keyGeneratedAt = timestamp("key_generated_at").default(Instant.now())
    
    /** When these keys should be rotated */
    val keyRotationDue = timestamp("key_rotation_due").nullable()
    
    val createdAt = timestamp("created_at").default(Instant.now())
    val updatedAt = timestamp("updated_at").default(Instant.now())
    
    override val primaryKey = PrimaryKey(serverId)
}
