package signallatexbot.model

import kotlinx.serialization.Serializable
import java.util.Base64

@Serializable
@JvmInline
value class Base64String constructor(val value: String) {
  override fun toString(): String = value
  val bytes: ByteArray get() = Base64.getDecoder().decode(value)
  companion object {
    fun create(src: ByteArray) = Base64String(Base64.getEncoder().encodeToString(src))
  }
}
