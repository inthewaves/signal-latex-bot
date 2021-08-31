package signallatexbot.model

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.SCrypt
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.JsonAddress
import signallatexbot.util.Hex

/**
 * A hashed identifier of SignalServiceAddresses ([JsonAddress]) for use in history records for the bot.
 *
 * In almost all cases, the identifier that will be hashed will be the user's UUID.
 */
@Serializable
@JvmInline
value class UserIdentifier private constructor(val value: String) {
    override fun toString(): String = value
    companion object {
        fun getIdentifierToUse(address: JsonAddress) =
            address.uuid ?: address.number ?: throw IllegalArgumentException("address missing both uuid and number")

        fun create(address: JsonAddress, salt: ByteArray): UserIdentifier {
            val originalIdentifier = getIdentifierToUse(address)
            return UserIdentifier(
                Hex.encode(SCrypt.generate(originalIdentifier.encodeToByteArray(), salt, 32768, 8, 1, 32))
            )
        }
    }
}
