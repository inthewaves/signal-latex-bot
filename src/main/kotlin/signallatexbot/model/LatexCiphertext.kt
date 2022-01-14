package signallatexbot.model

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.KeysetHandle
import kotlinx.serialization.Serializable
import signallatexbot.core.BotConfig

@Serializable
@JvmInline
value class LatexCiphertext constructor(val ciphertext: Base64String) {
  /**
   * @throws java.security.GeneralSecurityException
   */
  fun decrypt(privateKeysetHandle: KeysetHandle, identifier: UserIdentifier): ByteArray {
    val hybridDecrypt = privateKeysetHandle.getPrimitive(HybridDecrypt::class.java)
    return hybridDecrypt.decrypt(ciphertext.bytes, identifier.value.encodeToByteArray())!!
  }

  companion object {
    fun fromPlaintext(botConfig: BotConfig, plaintext: String, identifier: UserIdentifier): LatexCiphertext {
      return LatexCiphertext(
        Base64String.create(
          botConfig.encryptWithPublicKey(plaintext, contextInfo = identifier.value.encodeToByteArray())
        )
      )
    }
  }
}
