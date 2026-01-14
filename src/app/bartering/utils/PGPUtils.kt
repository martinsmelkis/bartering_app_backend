package app.bartering.utils

import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.jcajce.JcaPGPPublicKeyRingCollection
import java.io.InputStream

object PGPUtils {

    fun getPGPPublicKeyFromString(str: String): PGPPublicKey? {
        var `in`: InputStream = str.byteInputStream()
        `in` = PGPUtil.getDecoderStream(`in`)

        val pgpPub = JcaPGPPublicKeyRingCollection(`in`)
        `in`.close()

        var key: PGPPublicKey? = null
        val rIt = pgpPub.keyRings
        while (key == null && rIt.hasNext()) {
            val kRing = rIt.next()
            val kIt = kRing.publicKeys
            while (key == null && kIt.hasNext()) {
                val k = kIt.next()

                if (k.isEncryptionKey) {
                    key = k
                }
            }
        }
        return key
    }




}