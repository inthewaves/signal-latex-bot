package signallatexbot.model

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.KeysetHandle
import kotlinx.serialization.Serializable
import signallatexbot.core.BotConfig

@Serializable
@JvmInline
value class HybridEncryptCiphertext private constructor(val ciphertext: ByteArray) {
    /**
     * @throws java.security.GeneralSecurityException
     */
    fun decrypt(keysetHandle: KeysetHandle, contextInfo: ByteArray = ByteArray(0)): ByteArray {
        val hybridDecrypt = keysetHandle.getPrimitive(HybridDecrypt::class.java)
        return hybridDecrypt.decrypt(ciphertext, contextInfo)!!
    }

    companion object {
        fun fromPlaintext(botConfig: BotConfig, plaintext: String): HybridEncryptCiphertext {
            return HybridEncryptCiphertext(botConfig.encryptWithPublicKey(plaintext, contextInfo = ByteArray(0)))
        }
    }
}