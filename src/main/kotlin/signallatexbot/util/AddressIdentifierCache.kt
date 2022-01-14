package signallatexbot.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.JsonAddress
import signallatexbot.db.BotDatabase
import signallatexbot.model.UserIdentifier
import kotlin.system.measureTimeMillis

class AddressIdentifierCache private constructor(
  private val map: LimitedLinkedHashMap<String, UserIdentifier>,
  private val salt: ByteArray,
  private val database: BotDatabase,
) {
  constructor(
    maxSize: Int = 1000,
    identifierHashSalt: ByteArray,
    database: BotDatabase,
  ) : this(LimitedLinkedHashMap(maxSize), identifierHashSalt, database)

  private val mutex = Mutex()
  suspend fun get(address: JsonAddress): UserIdentifier = mutex.withLock {
    map.computeIfAbsent(UserIdentifier.getIdentifierToUse(address)) {
      val result: UserIdentifier
      val timeToGenerate = measureTimeMillis { result = UserIdentifier.create(address, salt) }
      val timeToInsert = measureTimeMillis { database.userQueries.insertOrIgnore(result) }
      println(
        "generated hashed identifier in $timeToGenerate ms for $result due to cache miss, " +
          "db insertion $timeToInsert ms"
      )
      result
    }
  }
}
