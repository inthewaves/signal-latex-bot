package signallatexbot.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class RequestId private constructor(val id: String) {
    val timestamp: Long get() = id.split("-").last().toLong()
    override fun toString(): String = id
    companion object {
        val timestampComparator = compareBy<RequestId> { it.timestamp }

        fun create(identifier: UserIdentifier) = RequestId("${identifier.value}-${System.currentTimeMillis()}")
    }
}