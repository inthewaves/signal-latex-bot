package signallatexbot.util

import java.util.TreeSet

fun <T : Comparable<T>> Sequence<T>.toTreeSet(): TreeSet<T> {
  return toCollection(TreeSet<T>())
}
