package signallatexbot.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
class RequestHistory(
    val identifier: BotIdentifier,
    /**
     * Send times in millis
     */
    val sendTimes: List<Long>
) {
    val mostRecentTime = sendTimes.lastOrNull()

    fun countRequestsInInterval(intervalSizeMillis: Long): Int {
        val now = System.currentTimeMillis()
        return sendTimes.asReversed()
            .asSequence()
            .takeWhile { now - it < intervalSizeMillis }
            .count()
    }

    fun leastRecentTimeInInterval(intervalSizeMillis: Long): Long? {
        val now = System.currentTimeMillis()
        return sendTimes.asReversed()
            .asSequence()
            .takeWhile { now - it < intervalSizeMillis }
            .lastOrNull()
    }

    fun copyWithNewSendTime(newTime: Long): RequestHistory {
        val newList = if (sendTimes.size > MAX_HISTORY_SIZE) {
            sendTimes.asSequence().drop(1).plus(newTime).toList()
        } else {
            sendTimes + newTime
        }
        return RequestHistory(identifier, newList)
    }

    fun writeToDisk() {
        if (!requestHistoryRootDir.exists() && !requestHistoryRootDir.mkdirs()) {
            throw IOException("Unable to make ${requestHistoryRootDir.absolutePath}")
        }

        val historyFile = File(requestHistoryRootDir, identifier.value)
        historyFile.writeText(Json.encodeToString(serializer(), this))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RequestHistory

        if (identifier != other.identifier) return false
        if (sendTimes != other.sendTimes) return false

        return true
    }

    override fun hashCode(): Int {
        var result = identifier.hashCode()
        result = 31 * result + sendTimes.hashCode()
        return result
    }

    override fun toString(): String {
        return "RequestHistory(identifier=$identifier, sendTimes=$sendTimes)"
    }

    companion object {
        private const val MAX_HISTORY_SIZE = 100

        private val requestHistoryRootDir = File("history")

        /**
         * @throws java.io.IOException
         * @throws SerializationException
         */
        fun readFromFile(identifier: BotIdentifier): RequestHistory {
            val historyFile = File(requestHistoryRootDir, identifier.value)
            return if (!historyFile.exists()) {
                RequestHistory(identifier, emptyList())
            } else {
                Json.decodeFromString(serializer(), historyFile.readText())
            }
        }
    }
}
