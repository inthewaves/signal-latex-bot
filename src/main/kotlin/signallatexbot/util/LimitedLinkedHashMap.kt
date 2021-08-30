package signallatexbot.util

import java.util.LinkedHashMap

class LimitedLinkedHashMap<K, V>(val maxEntries: Int) : LinkedHashMap<K, V>() {
    init {
        require(maxEntries > 1) { "max entries must be greater than 1 (was given $maxEntries)" }
    }

    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
        return size > maxEntries
    }
}
