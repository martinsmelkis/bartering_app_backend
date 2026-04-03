package app.bartering.dashboard_admin_compliance.utils

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.util.Base64

object CryptoUtils {

    fun parseEcPrivateKeyFromHex(privateKeyHex: String): ECPrivateKey {
        val normalized = privateKeyHex.trim().removePrefix("0x").ifBlank { "0" }
        val d = BigInteger(normalized, 16)

        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        val ecSpec = params.getParameterSpec(ECParameterSpec::class.java)

        val keyFactory = KeyFactory.getInstance("EC")
        val keySpec = ECPrivateKeySpec(d, ecSpec)
        return keyFactory.generatePrivate(keySpec) as ECPrivateKey
    }

    fun signChallenge(privateKey: ECPrivateKey, challenge: String): String {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(challenge.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signature.sign())
    }
}