package signallatexbot.model

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import signallatexbot.core.BotConfig
import signallatexbot.model.RequestHistory.Companion.MAX_HISTORY_SIZE
import signallatexbot.model.RequestHistory.Companion.MAX_TIMEOUT_HISTORY_SIZE
import signallatexbot.serialization.TreeSetSerializer
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.SortedSet
import java.util.TreeSet
import java.util.concurrent.TimeUnit

/**
 * Encapsulates the LaTeX request history file for one Signal user. Users are identified by their [UserIdentifier],
 * which is a hashed identifier.
 *
 * The history is queried for reasons such as monitoring and reacting to remote-delete requests from clients. History
 * records do not exceed the limits given by [MAX_HISTORY_SIZE] and [MAX_TIMEOUT_HISTORY_SIZE].
 */
@Serializable
class RequestHistory private constructor(
    /**
     * The identifier for this history file.
     */
    val identifier: UserIdentifier,
    /**
     * The set of requests that were either successful or had an error.
     */
    @SerialName("history")
    @Serializable(TreeSetSerializer::class)
    private val _history: TreeSet<Entry> = sortedSetOf(),
    /**
     * The set of requests that took too long to complete. The intersection with this set and [_history] should
     * be empty.
     */
    @SerialName("timedOut")
    @Serializable(TreeSetSerializer::class)
    private val _timedOutRequests: TreeSet<TimedOutEntry> = sortedSetOf()
) {
    val history: SortedSet<Entry> by lazy { Collections.unmodifiableSortedSet(_history) }

    val timedOut: SortedSet<TimedOutEntry> by lazy { Collections.unmodifiableSortedSet(_timedOutRequests) }

    sealed interface BaseEntry : Comparable<BaseEntry> {
        /**
         * The client-supplied timestamp of the message, used to identify messages for reactions, remote deletes,
         * etc.
         */
        val clientSentTimestamp: Long

        /**
         * The time that the server received the message. This will be used for comparisons and rate limiting.
         */
        val serverReceiveTime: Long

        /**
         * Our timestamp for the message reply. This will be used to identify our sent messages to act on remote
         * delete requests.
         */
        val replyMessageTimestamp: Long

        override fun compareTo(other: BaseEntry): Int = serverReceiveTime.compareTo(other.serverReceiveTime)
    }

    @Serializable
    data class Entry(
        override val clientSentTimestamp: Long,
        override val serverReceiveTime: Long,
        override val replyMessageTimestamp: Long
    ) : BaseEntry {
        fun toTimedOutEntry(botConfig: BotConfig, plaintextLatex: String, identifier: UserIdentifier) = TimedOutEntry(
            clientSentTimestamp,
            serverReceiveTime,
            replyMessageTimestamp,
            LatexCiphertext.fromPlaintext(botConfig, plaintextLatex, identifier)
        )
    }

    @Serializable
    data class TimedOutEntry(
        override val clientSentTimestamp: Long,
        override val serverReceiveTime: Long,
        override val replyMessageTimestamp: Long,
        /**
         * The LaTeX input that took too long to generate. We keep this here for troubleshooting / monitoring reasons.
         * We ensure that it is encrypted and that we never print user's LaTeX input to stdout / stderr.
         */
        val latexCiphertext: LatexCiphertext
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

    fun toBuilder(): Builder = Builder(this)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RequestHistory

        if (identifier != other.identifier) return false
        if (_history != other._history) return false
        if (_timedOutRequests != other._timedOutRequests) return false

        return true
    }

    override fun hashCode(): Int {
        var result = identifier.hashCode()
        result = 31 * result + _history.hashCode()
        result = 31 * result + _timedOutRequests.hashCode()
        return result
    }

    override fun toString(): String {
        return "RequestHistory(identifier=$identifier, history=$history, timedOut=$timedOut)"
    }

    companion object {
        private const val MAX_HISTORY_SIZE = 100
        private const val MAX_TIMEOUT_HISTORY_SIZE = 100

        val requestHistoryRootDir = File("history")

        /**
         * @throws java.io.IOException
         * @throws SerializationException
         */
        fun readFromFile(identifier: UserIdentifier): RequestHistory {
            val historyFile = File(requestHistoryRootDir, identifier.value)
            return if (!historyFile.exists()) {
                RequestHistory(identifier, TreeSet(), TreeSet())
            } else {
                Json.decodeFromString(serializer(), historyFile.readText())
            }
        }
    }

    class Builder(private val identifier: UserIdentifier) {
        constructor(requestHistory: RequestHistory) : this(requestHistory.identifier) {
            @Suppress("UNCHECKED_CAST")
            this.history = requestHistory._history.clone() as TreeSet<Entry>
            @Suppress("UNCHECKED_CAST")
            this.timedOutRequests = requestHistory._timedOutRequests.clone() as TreeSet<TimedOutEntry>
        }
        private var history: TreeSet<Entry> = sortedSetOf()
        private var timedOutRequests: TreeSet<TimedOutEntry> = sortedSetOf()

        suspend fun removeEntriesOlderThan(durationAgo: Long, timeUnit: TimeUnit): Builder = coroutineScope {
            val timestamp = System.currentTimeMillis() - timeUnit.toMillis(durationAgo)
            launch {
                history.removeIf { it.serverReceiveTime < timestamp }
            }
            launch {
                timedOutRequests.removeIf { it.serverReceiveTime < timestamp }
            }
            this@Builder
        }

        fun addHistoryEntry(entry: Entry): Builder {
            if (history.size >= MAX_HISTORY_SIZE) {
                history.remove(history.first())
            }
            history.add(entry)
            return this
        }

        fun addTimedOutEntry(entry: TimedOutEntry): Builder {
            if (timedOutRequests.size >= MAX_TIMEOUT_HISTORY_SIZE) {
                timedOutRequests.remove(timedOutRequests.first())
            }
            timedOutRequests.add(entry)
            return this
        }

        fun build(): RequestHistory {
            return RequestHistory(identifier, history, timedOutRequests)
        }
    }
}
