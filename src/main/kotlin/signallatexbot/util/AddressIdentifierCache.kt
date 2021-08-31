package signallatexbot.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.JsonAddress
import signallatexbot.model.UserIdentifier
import kotlin.system.measureTimeMillis

class AddressIdentifierCache private constructor(
    private val map: LimitedLinkedHashMap<String, UserIdentifier>,
    private val salt: ByteArray
) {
    constructor(
        maxSize: Int = 1000,
        identifierHashSalt: ByteArray
    ) : this(LimitedLinkedHashMap(maxSize), identifierHashSalt)

    private val mutex = Mutex()
    suspend fun get(address: JsonAddress): UserIdentifier = mutex.withLock {
        map.getOrPut(UserIdentifier.getIdentifierToUse(address)) {
            val result: UserIdentifier
            val time = measureTimeMillis { result = UserIdentifier.create(address, salt) }
            println("generated hashed identifier in $time ms for $result due to cache miss")
            result
        }
    }
}