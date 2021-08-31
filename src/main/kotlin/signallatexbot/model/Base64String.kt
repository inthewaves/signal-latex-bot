package signallatexbot.model

import kotlinx.serialization.Serializable
import java.util.Base64

@Serializable
@JvmInline
value class Base64String private constructor(val value: String) {
    override fun toString(): String = value
    companion object {
        fun create(src: ByteArray) = Base64String(Base64.getEncoder().encodeToString(src))
    }
}