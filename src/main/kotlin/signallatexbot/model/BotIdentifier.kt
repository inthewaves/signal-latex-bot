package signallatexbot.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.SCrypt
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.JsonAddress
import signallatexbot.util.Hex
import java.lang.IllegalArgumentException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

@Serializable
@JvmInline
value class BotIdentifier private constructor(val value: String) {
    override fun toString(): String = value
    companion object {
        fun getIdentifierToUse(address: JsonAddress) = address.uuid
            ?: address.number
            ?: throw IllegalArgumentException("address missing both uuid and number")

        fun create(address: JsonAddress, salt: ByteArray): BotIdentifier {
            val originalIdentifier = getIdentifierToUse(address)
            return BotIdentifier(
                Hex.encode(SCrypt.generate(originalIdentifier.encodeToByteArray(), salt, 32768, 8, 1, 32))
            )
        }
    }
}
