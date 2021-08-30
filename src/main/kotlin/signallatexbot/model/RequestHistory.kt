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
import java.lang.IllegalStateException
import java.util.Collections
import java.util.SortedSet
import java.util.TreeSet

@Serializable
data class RequestHistory(
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

    fun writeToDisk() {
        if (!requestHistoryRootDir.exists() && !requestHistoryRootDir.mkdirs()) {
            throw IOException("Unable to make ${requestHistoryRootDir.absolutePath}")
        }

        val historyFile = File(requestHistoryRootDir, identifier.value)
        historyFile.writeText(Json.encodeToString(serializer(), this))
    }

    @Suppress("UNCHECKED_CAST")
    fun toBuilder(): Builder =
        Builder(identifier, _history.clone() as TreeSet<Entry>, _timedOutRequests.clone() as TreeSet<TimedOutEntry>)

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

    class Builder {
        constructor()
        constructor(identifier: BotIdentifier, history: TreeSet<Entry>, timedOutRequests: TreeSet<TimedOutEntry>) {
            this.identifier = identifier
            this.history = history
            this.timedOutRequests = timedOutRequests
        }

        var identifier: BotIdentifier? = null
        var history: TreeSet<Entry> = sortedSetOf()
        var timedOutRequests: TreeSet<TimedOutEntry> = sortedSetOf()

        fun addHistoryEntry(entry: Entry): Builder {
            history.add(entry)
            return this
        }

        fun addTimedOutEntry(entry: TimedOutEntry): Builder {
            timedOutRequests.add(entry)
            return this
        }

        fun build(): RequestHistory {
            val id = identifier ?: error { "missing identifier" }
            return RequestHistory(id, history, timedOutRequests)
        }
    }
}
