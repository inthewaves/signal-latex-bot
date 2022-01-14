package signallatexbot.model

import kotlinx.serialization.Serializable
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.IncomingMessage
import kotlin.coroutines.CoroutineContext

@Serializable
@JvmInline
value class RequestId constructor(val id: String) : CoroutineContext.Element {
  override val key: CoroutineContext.Key<RequestId> get() = Companion
  val timestamp: Long get() = id.split("-").last().toLong()
  override fun toString(): String = id
  companion object : CoroutineContext.Key<RequestId> {
    val timestampComparator = compareBy<RequestId> { it.timestamp }

    fun create(identifier: UserIdentifier, incomingMessage: IncomingMessage): RequestId {
      return RequestId("${identifier.value}-${incomingMessage.data.serverReceiverTimestamp}")
    }
  }
}
