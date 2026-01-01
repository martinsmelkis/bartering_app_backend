package org.barter.utils

import org.bouncycastle.util.encoders.Base64
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

object CryptoUtils {

    /**
     * Converts a raw, uncompressed 65-byte public key (or its Base64 representation)
     * into a proper ECPublicKey object by wrapping it with the X.509 SPKI header.
     *
     * This assumes the key is for the secp256r1 (P-256) curve.
     *
     * @param rawPublicKeyB64 The Base64 encoded raw public key.
     * @return An ECPublicKey instance.
     */
    fun convertRawB64KeyToECPublicKey(rawPublicKeyB64: String): ECPublicKey {
        // The standard ASN.1/DER header for a secp256r1 public key in X.509 format.
        // This specifies the algorithm (EC) and the curve name (prime256v1).
        val spkiHeader = byteArrayOf(
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86.toByte(), 0x48,
            0xCE.toByte(), 0x3D, 0x02, 0x01,
            0x06, 0x08, 0x2A, 0x86.toByte(), 0x48,
            0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00
        )

        // Decode the Base64 raw key provided from the database
        val rawPublicKeyBytes = Base64.decode(rawPublicKeyB64)

        // Combine the header and the raw key to form a full X.509 key
        val x509KeyBytes = spkiHeader + rawPublicKeyBytes

        // Now, create the key using the standard Java crypto classes
        val keySpec = X509EncodedKeySpec(x509KeyBytes)
        val keyFactory = KeyFactory.getInstance("EC")

        return keyFactory.generatePublic(keySpec) as ECPublicKey
    }
}