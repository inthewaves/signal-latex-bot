/*
 * Much of this file is from
 * https://github.com/JakeWharton/dodo/blob/trunk/src/main/kotlin/com/jakewharton/dodo/db.kt
 */

package signallatexbot.db

import com.squareup.sqldelight.ColumnAdapter
import com.squareup.sqldelight.Query
import com.squareup.sqldelight.sqlite.driver.JdbcSqliteDriver
import org.sqlite.SQLiteConfig
import signallatexbot.model.Base64String
import signallatexbot.model.LatexCiphertext
import signallatexbot.model.RequestId
import signallatexbot.model.UserIdentifier
import java.nio.file.Paths
import java.util.Properties

private const val versionPragma = "user_version"

val DEFAULT_DATABASE_PATH =
    Paths.get(Paths.get("").toAbsolutePath().normalize().toString(), "botdb.db").toString()

/**
 * Executes the query as a sequence and returns the result of the [sequenceHandler].
 *
 * The [sequenceHandler] uses the database cursor, and then the cursor is closed when the handler is finished.
 * Since the cursor is closed after the handler is finished, the [Sequence] in the [sequenceHandler] should NOT be used
 * outside of the handler.
 *
 * The returned sequence is constrained to be iterated only once.
 */
inline fun <T : Any, R> Query<T>.executeAsSequence(sequenceHandler: (sequence: Sequence<T>) -> R): R {
    execute().use { sqlCursor ->
        val sequence = object : Sequence<T> {
            override fun iterator(): Iterator<T> = object : Iterator<T> {
                var cachedHasNext: Boolean = sqlCursor.next()

                override fun next(): T {
                    if (!cachedHasNext) throw NoSuchElementException()
                    val result: T = mapper(sqlCursor)
                    cachedHasNext = sqlCursor.next()
                    return result
                }

                override fun hasNext(): Boolean {
                    return cachedHasNext
                }
            }
        }.constrainOnce()

        return sequenceHandler(sequence)
    }
}

val SQLITE3_CONFIG: Properties = SQLiteConfig().apply {
    enforceForeignKeys(true)
    setJournalMode(SQLiteConfig.JournalMode.WAL)
    setSynchronous(SQLiteConfig.SynchronousMode.FULL)
}.toProperties()

class DbWrapper(
    val db: BotDatabase,
    val driver: JdbcSqliteDriver
) {
    fun doWalCheckpointTruncate() {
        driver.execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0)
    }
}

suspend inline fun withDatabase(
    path: String = DEFAULT_DATABASE_PATH,
    crossinline block: suspend (db: DbWrapper) -> Unit
) {
    JdbcSqliteDriver("jdbc:sqlite:$path", SQLITE3_CONFIG).use { driver ->
        migrateIfNeeded(driver)

        driver.apply {
            execute(null, "ANALYZE", 0)
            execute(null, "VACUUM", 0)
            execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0)
        }

        block(
            DbWrapper(
                BotDatabase(
                    driver = driver,
                    RequestAdapter = requestsAdapter,
                    UserAdapter = userAdapter
                ),
                driver
            )
        )
    }
}

fun migrateIfNeeded(driver: JdbcSqliteDriver) {
    val oldVersion: Int =
        driver.executeQuery(null, "PRAGMA $versionPragma", 0).use { cursor ->
            if (cursor.next()) {
                cursor.getLong(0)?.toInt()
            } else {
                null
            }
        } ?: 0

    val newVersion: Int = BotDatabase.Schema.version

    if (oldVersion == 0) {
        println("Creating DB version $newVersion")
        BotDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA $versionPragma=$newVersion", 0)
    } else if (oldVersion < newVersion) {
        println("Migrating DB from version $oldVersion to $newVersion!")
        BotDatabase.Schema.migrate(driver, oldVersion, newVersion)
        driver.execute(null, "PRAGMA $versionPragma=$newVersion", 0)
    }
}

private val latexCiphertextAdapter = object : ColumnAdapter<LatexCiphertext, String> {
    override fun decode(databaseValue: String): LatexCiphertext = LatexCiphertext(Base64String(databaseValue))
    override fun encode(value: LatexCiphertext): String = value.ciphertext.value
}

private val requestIdAdapter = object : ColumnAdapter<RequestId, String> {
    override fun decode(databaseValue: String): RequestId = RequestId(databaseValue)
    override fun encode(value: RequestId): String = value.id
}

private val userIdentifierAdapter = object : ColumnAdapter<UserIdentifier, String> {
    override fun decode(databaseValue: String): UserIdentifier = UserIdentifier(databaseValue)
    override fun encode(value: UserIdentifier): String = value.value
}

val requestsAdapter = Request.Adapter(
    userIdAdapter = userIdentifierAdapter ,
    latexCiphertextAdapter = latexCiphertextAdapter
)

val userAdapter = User.Adapter(identifierAdapter = userIdentifierAdapter)
