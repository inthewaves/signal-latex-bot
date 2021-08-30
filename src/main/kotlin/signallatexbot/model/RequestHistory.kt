package signallatexbot.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import signallatexbot.serialization.TreeSetSerializer
import signallatexbot.util.toTreeSet
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.SortedSet
import java.util.TreeSet

@Serializable
class RequestHistory(
    val identifier: BotIdentifier,
    @SerialName("history")
    @Serializable(TreeSetSerializer::class)
    private val _history: TreeSet<Entry> = sortedSetOf(),
    @SerialName("timedOut")
    @Serializable(TreeSetSerializer::class)
    private val _timedOutRequests: TreeSet<TimedOutEntry> = sortedSetOf()
) {
    @Transient
    val history: SortedSet<Entry> = Collections.unmodifiableSortedSet(_history)

    @Transient
    val timedOut: SortedSet<TimedOutEntry> = Collections.unmodifiableSortedSet(_timedOutRequests)

    interface BaseEntry : Comparable<BaseEntry> {
        val serverReceiveTime: Long
        val requestLocalTime: Long

        override fun compareTo(other: BaseEntry): Int = serverReceiveTime.compareTo(other.serverReceiveTime)
    }

    @Serializable
    data class Entry(
        override val serverReceiveTime: Long,
        override val requestLocalTime: Long
    ) : BaseEntry {
        fun toTimedOutEntry(latex: String) = TimedOutEntry(
            serverReceiveTime,
            requestLocalTime,
            Base64String.create(latex.encodeToByteArray())
        )
    }

    @Serializable
    data class TimedOutEntry(
        override val serverReceiveTime: Long,
        override val requestLocalTime: Long,
        val latex: Base64String
    ) : BaseEntry

    val mostRecentEntry: Entry? = try {
        _history.last()
    } catch (e: NoSuchElementException) {
        null
    }

    val mostRecentTimeOutEntry: TimedOutEntry? = try {
        _timedOutRequests.last()
    } catch (e: NoSuchElementException) {
        null
    }

    private fun <T : BaseEntry> filterTreeSetInterval(
        treeSet: TreeSet<T>,
        entryToCompareAgainst: Entry,
        intervalMillis: Long
    ): SortedSet<T> {
        val leastRecentEntry = treeSet.descendingIterator()
            .asSequence()
            .lastOrNull {
                val delta = entryToCompareAgainst.serverReceiveTime - it.serverReceiveTime
                delta in 0..intervalMillis
            }
        val sortedSet = if (leastRecentEntry != null) {
            treeSet.tailSet(leastRecentEntry)
        } else {
            TreeSet()
        }
        return Collections.unmodifiableSortedSet(sortedSet)
    }

    fun entriesWithinInterval(entryToCompareAgainst: Entry, intervalMillis: Long): SortedSet<Entry> {
        return filterTreeSetInterval(_history, entryToCompareAgainst, intervalMillis)
    }

    fun timedOutEntriesWithinInterval(entryToCompareAgainst: Entry, intervalMillis: Long): SortedSet<TimedOutEntry> {
        return filterTreeSetInterval(_timedOutRequests, entryToCompareAgainst, intervalMillis)
    }

    fun copyWithNewHistoryEntry(newEntry: Entry): RequestHistory {
        val newSet = if (history.size > MAX_HISTORY_SIZE) {
            _history.asSequence().drop(1).plus(newEntry).toTreeSet()
        } else {
            _history.asSequence().plus(newEntry).toTreeSet()
        }
        return RequestHistory(identifier, newSet, _timedOutRequests)
    }

    fun copyWithNewTimeoutEntry(newEntry: TimedOutEntry): RequestHistory {
        val newSet = if (history.size > MAX_HISTORY_SIZE) {
            _timedOutRequests.asSequence().drop(1).plus(newEntry).toTreeSet()
        } else {
            _timedOutRequests.asSequence().plus(newEntry).toTreeSet()
        }
        return RequestHistory(identifier, _history, newSet)
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
        if (history != other.history) return false

        return true
    }

    override fun hashCode(): Int {
        var result = identifier.hashCode()
        result = 31 * result + history.hashCode()
        return result
    }

    override fun toString(): String {
        return "RequestHistory(identifier=$identifier, sendTimes=$history)"
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
                RequestHistory(identifier, TreeSet(), TreeSet())
            } else {
                Json.decodeFromString(serializer(), historyFile.readText())
            }
        }
    }
}
