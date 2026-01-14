package app.bartering.features.federation.crypto

import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import java.io.StringReader
import java.io.StringWriter
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher

/**
 * Cryptographic utilities for federation operations.
 * Handles key generation, signature creation/verification, and data encryption.
 */
object FederationCrypto {
    
    private const val KEY_ALGORITHM = "RSA"
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    
    init {
        // Ensure BouncyCastle provider is registered
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
    }
    
    /**
     * Generates a new RSA key pair for server identity.
     */
    fun generateKeyPair(keySize: Int = 2048): KeyPair {
        val keyGen = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        keyGen.initialize(keySize, SecureRandom())
        return keyGen.generateKeyPair()
    }
    
    /**
     * Converts a public key to PEM format string.
     */
    fun publicKeyToPem(publicKey: PublicKey): String {
        val stringWriter = StringWriter()
        JcaPEMWriter(stringWriter).use { pemWriter ->
            pemWriter.writeObject(publicKey)
        }
        return stringWriter.toString()
    }
    
    /**
     * Converts a private key to PEM format string.
     */
    fun privateKeyToPem(privateKey: PrivateKey): String {
        val stringWriter = StringWriter()
        JcaPEMWriter(stringWriter).use { pemWriter ->
            pemWriter.writeObject(privateKey)
        }
        return stringWriter.toString()
    }
    
    /**
     * Parses a PEM-formatted public key string.
     */
    fun pemToPublicKey(pemString: String): PublicKey {
        val reader = StringReader(pemString)
        PEMParser(reader).use { pemParser ->
            val pemObject = pemParser.readObject()
            val converter = JcaPEMKeyConverter()
            
            return when (pemObject) {
                is org.bouncycastle.asn1.x509.SubjectPublicKeyInfo -> converter.getPublicKey(pemObject)
                else -> throw IllegalArgumentException("Invalid PEM format for public key")
            }
        }
    }
    
    /**
     * Parses a PEM-formatted private key string.
     */
    fun pemToPrivateKey(pemString: String): PrivateKey {
        val reader = StringReader(pemString)
        PEMParser(reader).use { pemParser ->
            val pemObject = pemParser.readObject()
            val converter = JcaPEMKeyConverter()
            
            return when (pemObject) {
                is PEMKeyPair -> converter.getPrivateKey(pemObject.privateKeyInfo)
                is org.bouncycastle.asn1.pkcs.PrivateKeyInfo -> converter.getPrivateKey(pemObject)
                else -> throw IllegalArgumentException("Invalid PEM format for private key")
            }
        }
    }
    
    /**
     * Signs data using a private key.
     * Returns Base64-encoded signature.
     */
    fun sign(data: String, privateKey: PrivateKey): String {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(data.toByteArray())
        return Base64.getEncoder().encodeToString(signature.sign())
    }
    
    /**
     * Verifies a signature using a public key.
     */
    fun verify(data: String, signatureBase64: String, publicKey: PublicKey): Boolean {
        return try {
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            signature.initVerify(publicKey)
            signature.update(data.toByteArray())
            val signatureBytes = Base64.getDecoder().decode(signatureBase64)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Creates a signature for a federation message.
     * Combines serverId, timestamp, and payload for signing.
     */
    fun signFederationMessage(
        serverId: String,
        timestamp: Long,
        payload: String,
        privateKey: PrivateKey
    ): String {
        val dataToSign = "$serverId|$timestamp|$payload"
        return sign(dataToSign, privateKey)
    }
    
    /**
     * Verifies a federation message signature.
     */
    fun verifyFederationMessage(
        serverId: String,
        timestamp: Long,
        payload: String,
        signatureBase64: String,
        publicKey: PublicKey
    ): Boolean {
        val dataToVerify = "$serverId|$timestamp|$payload"
        return verify(dataToVerify, signatureBase64, publicKey)
    }
    
    /**
     * Generates a hash of data for integrity checking.
     */
    fun hash(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data.toByteArray())
        return Base64.getEncoder().encodeToString(hashBytes)
    }
    
    /**
     * Generates a unique server ID (UUID format).
     */
    fun generateServerId(): String {
        return UUID.randomUUID().toString()
    }
    
    /**
     * Generates a federation agreement hash from two server IDs and scopes.
     */
    fun generateAgreementHash(
        serverIdA: String,
        serverIdB: String,
        scopes: String,
        timestamp: Long
    ): String {
        val data = "$serverIdA|$serverIdB|$scopes|$timestamp"
        return hash(data)
    }
}
