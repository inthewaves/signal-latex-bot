package signallatexbot

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.inthewaves.kotlinsignald.Recipient
import org.inthewaves.kotlinsignald.Signal
import org.inthewaves.kotlinsignald.SocketUnavailableException
import org.inthewaves.kotlinsignald.clientprotocol.SignaldException
import org.inthewaves.kotlinsignald.clientprotocol.v0.structures.JsonAttachment
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.ExceptionWrapper
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.IncomingMessage
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.JsonQuote
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.ListenerState
import org.inthewaves.kotlinsignald.subscription.signalMessagesChannel
import org.scilab.forge.jlatexmath.ParseException
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import signallatexbot.model.BotIdentifier
import signallatexbot.model.RequestHistory
import signallatexbot.model.RequestId
import signallatexbot.util.LimitedLinkedHashMap
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Insets
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipalNotFoundException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JLabel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.path.absolute
import kotlin.math.roundToLong
import kotlin.random.asKotlinRandom
import kotlin.random.nextLong
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

val latexGenerationThreadGroup = ThreadGroup("latex-generation").apply {
    // isDaemon = true
}

private const val USAGE = "usage: signal-latex-bot accountId outputDirectory"
private const val SALT_FILENAME = "identifier-hash-salt"

private val TYPING_INDICATOR_START_DELAY_RANGE_MILLIS = 250L..500L
private val REPLY_DELAY_RANGE_MILLIS = 500L..1500L
private const val LATEX_GENERATION_TIMEOUT_MILLIS = 2000L
private const val MAX_CONCURRENT_LATEX_GENERATION = 12
private const val EXECUTOR_FIXED_THREAD_POOL_COUNT = MAX_CONCURRENT_LATEX_GENERATION + 2

sealed interface Reply {
    abstract val originalMessage: IncomingMessage
    abstract val replyRecipient: Recipient
    abstract val requestId: RequestId
    abstract val delayRange: LongRange

    sealed interface ErrorReply : Reply {
        val errorMessage: String
    }

    data class LatexReply (
        override val originalMessage: IncomingMessage,
        override val replyRecipient: Recipient,
        override val requestId: RequestId,
        override val delayRange: LongRange,
        val latexImagePath: String
    ) : Reply

    data class Error(
        override val originalMessage: IncomingMessage,
        override val replyRecipient: Recipient,
        override val requestId: RequestId,
        override val delayRange: LongRange,
        override val errorMessage: String
    ) : ErrorReply

    data class TryAgainMessage(
        override val originalMessage: IncomingMessage,
        override val replyRecipient: Recipient,
        override val requestId: RequestId,
        override val delayRange: LongRange,
        private val retryAfterTimestamp: Long,
        private val currentTimestampUsed: Long,
    ) : ErrorReply {
        override val errorMessage: String
            get() {
                val seconds = TimeUnit.MILLISECONDS.toSeconds(retryAfterTimestamp - currentTimestampUsed)
                return if (seconds == 1L) {
                    "Too many requests! Try again in $seconds second."
                } else if (seconds > 0L) {
                    "Too many requests! Try again in $seconds seconds."
                } else {
                    "Too many requests! Try again."
                }
            }
    }
}

val secureRandom = SecureRandom()
val secureKotlinRandom = secureRandom.asKotlinRandom()

@OptIn(ObsoleteCoroutinesApi::class)
fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println(USAGE)
        exitProcess(1)
    }

    val outputDirectory = File(args[1])
    if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
        println("failed to make output directory ${outputDirectory.absolutePath}")
        exitProcess(1)
    }

    val photoDirPermissions = PosixFilePermissions.fromString("rwxr-x---")
    val currentPerms = Files.getPosixFilePermissions(outputDirectory.toPath())
    if (currentPerms != photoDirPermissions) {
        Files.setPosixFilePermissions(outputDirectory.toPath(), photoDirPermissions)
    }

    val lookupService = FileSystems.getDefault().userPrincipalLookupService
    val group = try {
        lookupService.lookupPrincipalByGroupName("signald")!!
    } catch (e: UserPrincipalNotFoundException) {
        println("unable to find UNIX group signald")
        exitProcess(1)
    }
    val fileAttributesView = Files.getFileAttributeView(outputDirectory.toPath(), PosixFileAttributeView::class.java)
    val currentGroupForDir = fileAttributesView.readAttributes().group()
    if (currentGroupForDir != group) {
        fileAttributesView.setGroup(group)
    }

    val signal = try {
        Signal(args.first())
    } catch (e: SignaldException) {
        if (e is SocketUnavailableException) {
            println("failed to connect to signald: socket is unavailable: ${e.message}")
        } else {
            println("failed to connect to signald: ${e.message}")
        }
        exitProcess(1)
    }

    val salt: ByteArray = run {
        val saltFilePath = Path.of(SALT_FILENAME)
        if (Files.isReadable(saltFilePath)) {
            println("reading salt from ${saltFilePath.absolute()}")
            Files.readAllBytes(saltFilePath)
        } else {
            println("generating salt and saving it to ${saltFilePath.absolute()}")
            ByteArray(32)
                .also(secureRandom::nextBytes)
                .also { Files.write(saltFilePath, it) }
        }
    }

    println("Starting bot")

    val executor = Executors.newFixedThreadPool(EXECUTOR_FIXED_THREAD_POOL_COUNT)
    try {
        runBlocking(executor.asCoroutineDispatcher()) {
            val latexGenerationSemaphore = Semaphore(permits = MAX_CONCURRENT_LATEX_GENERATION)

            val identifierMutexes = hashMapOf<BotIdentifier, Mutex>()
            val identifierMutexesMutex = Mutex()
            launch {
                while (isActive) {
                    identifierMutexesMutex.withLock {
                        val iterator = identifierMutexes.iterator()
                        for ((id, mutex) in iterator) {
                            if (!mutex.isLocked) {
                                iterator.remove()
                                println(
                                    "removed unused mutex for $id. " +
                                            "identifierMutexes size is now ${identifierMutexes.size}"
                                )
                            }
                        }
                    }

                    delay(TimeUnit.MINUTES.toMillis(2L))
                }
            }

            suspend fun sendMessage(reply: Reply) {
                val msgData: IncomingMessage.Data = reply.originalMessage.data
                val delayMillis = secureKotlinRandom.nextLong(reply.delayRange)
                delay(delayMillis)
                runInterruptible {
                    fun handleError(errorReply: Reply.ErrorReply) {
                        println(
                            "sending LaTeX request failure for ${errorReply.requestId};" +
                                    " delayMillis was $delayMillis. " +
                                    "Failure reason: [${errorReply.errorMessage}]"
                        )
                        signal.send(
                            recipient = errorReply.replyRecipient,
                            messageBody = errorReply.errorMessage,
                            quote = JsonQuote(
                                id = msgData.timestamp,
                                author = msgData.source,
                                text = msgData.dataMessage?.body
                            )
                        )
                    }

                    when (reply) {
                        is Reply.Error -> handleError(reply)
                        is Reply.TryAgainMessage -> handleError(reply)
                        is Reply.LatexReply -> {
                            try {
                                println("sending LaTeX request for ${reply.requestId}; delayMillis was $delayMillis")
                                signal.send(
                                    recipient = reply.replyRecipient,
                                    messageBody = "",
                                    attachments = listOf(JsonAttachment(filename = reply.latexImagePath)),
                                    quote = JsonQuote(
                                        id = msgData.timestamp,
                                        author = msgData.source,
                                        text = msgData.dataMessage?.body
                                    )
                                )
                            } finally {
                                Files.deleteIfExists(Path.of(reply.latexImagePath))
                            }
                        }
                    }
                }
            }

            val addressToIdentifierCache = LimitedLinkedHashMap<String, BotIdentifier>(100)
            val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
                System.err.println(
                    "Uncaught exception during generation: ${throwable.stackTraceToString()}"
                )
            }

            supervisorScope {
                val messageChannel = signalMessagesChannel(signal)
                messageChannel.consumeEach { message ->
                    when (message) {
                        is IncomingMessage -> {
                            message.data.dataMessage?.reaction?.let { jsonReaction ->
                                if (jsonReaction.targetAuthor?.uuid == signal.accountInfo!!.address?.uuid) {
                                    println("got a reaction to our own message")
                                }
                            }

                            val msgId = message.data.timestamp
                            if (msgId == null) {
                                println("received a message without a timestamp")
                                return@consumeEach
                            }
                            val serverReceiveTimestamp = message.data.serverReceiverTimestamp
                            if (serverReceiveTimestamp == null) {
                                println("received a message without a serverReceiveTimestamp")
                                return@consumeEach
                            }

                            val source = message.data.source?.takeUnless { it.uuid == null || it.number == null }
                            if (source == null) {
                                println("received a message without a UUID or a number")
                                return@consumeEach
                            }

                            val latexBodyInput = message.data.dataMessage?.body
                            if (latexBodyInput == null) {
                                println("received a message without a body")
                                return@consumeEach
                            }

                            val replyRecipient = try {
                                Recipient.forReply(message)
                            } catch (e: NullPointerException) {
                                println("failed to get reply recipient")
                                return@consumeEach
                            }

                            val identifier = addressToIdentifierCache
                                .getOrPut(BotIdentifier.getIdentifierToUse(source)) {
                                    val result: BotIdentifier
                                    val time = measureTimeMillis { result = BotIdentifier.create(source, salt) }
                                    println("generated hashed identifier in $time ms for $result due to cache miss")
                                    result
                                }

                            launch {
                                try {
                                    runInterruptible {
                                        signal.markRead(source, listOf(msgId))
                                    }
                                } catch (e: IOException) {
                                    System.err.println("failed to send read receipt: ${e.stackTraceToString()}")
                                }
                            }

                            launch(exceptionHandler) {
                                val userMutex = identifierMutexesMutex.withLock {
                                    identifierMutexes.getOrPut(identifier) { Mutex() }
                                }

                                userMutex.withLock {
                                    delay(secureKotlinRandom.nextLong(TYPING_INDICATOR_START_DELAY_RANGE_MILLIS))
                                    try {
                                        runInterruptible {
                                            signal.typing(replyRecipient, isTyping = true)
                                        }
                                    } catch (e: IOException) {
                                        System.err.println(
                                            "failed to send typing indicator: ${e.stackTraceToString()}"
                                        )
                                    }


                                    val existingHistoryForUser = RequestHistory.readFromFile(identifier)
                                    val newHistoryEntry = RequestHistory.Entry(
                                        serverReceiveTime = serverReceiveTimestamp,
                                        requestLocalTime = System.currentTimeMillis(),
                                    )
                                    val newHistory = existingHistoryForUser.copyWithNewHistoryEntry(newHistoryEntry)
                                        .apply { writeToDisk() }
                                    val requestId = RequestId.create(identifier)
                                    println("received LaTeX request $requestId")

                                    val minuteMillis = TimeUnit.MINUTES.toMillis(1)
                                    val entriesWithinLastMinute = existingHistoryForUser
                                        .entriesWithinInterval(newHistoryEntry, minuteMillis)
                                    println(
                                        "entriesWithinLastMinute: " +
                                                "${entriesWithinLastMinute.map { it.serverReceiveTime }}"
                                    )

                                    val numEntriesInLastMin = entriesWithinLastMinute.size
                                    val sendDelayRange: LongRange = if (numEntriesInLastMin != 0) {
                                        val (baseStart, baseEnd) = REPLY_DELAY_RANGE_MILLIS
                                            .let { it.first to it.last }
                                        val extraSeconds = (numEntriesInLastMin * numEntriesInLastMin / 18.0)
                                            .coerceAtMost(10.0)
                                        val delayAddition: Long = (extraSeconds * 1000L).roundToLong()
                                        println("request $requestId is getting an extra $delayAddition ms of delay")
                                        (baseStart + delayAddition)..(baseEnd + delayAddition)
                                    } else {
                                        REPLY_DELAY_RANGE_MILLIS
                                    }

                                    val hardLimitThreshold = 10

                                    val timedOutEntriesInLastMin = existingHistoryForUser
                                        .timedOutEntriesWithinInterval(newHistoryEntry, minuteMillis)
                                    if (
                                        entriesWithinLastMinute.size >= hardLimitThreshold ||
                                        timedOutEntriesInLastMin.isNotEmpty()
                                    ) {
                                        val mostRecentTimeout = timedOutEntriesInLastMin.last()
                                        val reason = if (mostRecentTimeout != null) {
                                            "sent a timed-out request in the last minute"
                                        } else {
                                            "$numEntriesInLastMin within last minute"
                                        }
                                        System.err.println(
                                            "request $requestId blocked --- too many requests ($reason)"
                                        )
                                        if (
                                            entriesWithinLastMinute.size == hardLimitThreshold ||
                                            mostRecentTimeout != null
                                        ) {
                                            val problemTime = mostRecentTimeout?.serverReceiveTime
                                                ?: entriesWithinLastMinute.first().serverReceiveTime
                                            sendMessage(
                                                Reply.TryAgainMessage(
                                                    requestId = requestId,
                                                    replyRecipient = replyRecipient,
                                                    originalMessage = message,
                                                    delayRange = sendDelayRange,
                                                    retryAfterTimestamp = problemTime + minuteMillis,
                                                    currentTimestampUsed = newHistoryEntry.serverReceiveTime
                                                )
                                            )
                                        }
                                        return@launch
                                    }

                                    val latexImagePath: String? = try {
                                        latexGenerationSemaphore.withPermit {
                                            println("generating LaTeX for request $requestId")
                                            withNewThreadAndTimeoutOrNull(
                                                timeoutMillis = LATEX_GENERATION_TIMEOUT_MILLIS,
                                                threadGroup = latexGenerationThreadGroup
                                            ) {
                                                val outputFile = File(outputDirectory, "$requestId.png")
                                                    .apply { deleteOnExit() }
                                                writeLatexToPng(latexBodyInput, outputFile)
                                                outputFile.absolutePath
                                            }

                                        }
                                    } catch (e: Exception) {
                                        System.err.println(
                                            "Failed to parse LaTeX for request $requestId: ${e.stackTraceToString()}"
                                        )

                                        val errorMsg = if (e is ParseException && !e.message.isNullOrBlank()) {
                                            "Failed to parse LaTeX: ${e.message}"
                                        } else {
                                            "Failed to parse LaTeX: misc error"
                                        }

                                        sendMessage(
                                            Reply.Error(
                                                requestId = requestId,
                                                replyRecipient = replyRecipient,
                                                originalMessage = message,
                                                delayRange = sendDelayRange,
                                                errorMessage = errorMsg
                                            )
                                        )
                                        return@withLock
                                    }

                                    if (latexImagePath == null) {
                                        newHistory
                                            .copyWithNewTimeoutEntry(newHistoryEntry.toTimedOutEntry(latexBodyInput))
                                            .writeToDisk()

                                        sendMessage(
                                            Reply.Error(
                                                requestId = requestId,
                                                replyRecipient = replyRecipient,
                                                originalMessage = message,
                                                delayRange = sendDelayRange,
                                                errorMessage = "Failed to parse LaTeX: Timed out"
                                            )
                                        )
                                    } else {
                                        sendMessage(
                                            Reply.LatexReply(
                                                requestId = requestId,
                                                replyRecipient = replyRecipient,
                                                originalMessage = message,
                                                delayRange = sendDelayRange,
                                                latexImagePath = latexImagePath
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        is ListenerState -> println("Received listener state update (connected=${message.data.connected})")
                        is ExceptionWrapper -> println("Received ExceptionWrapper: (${message.data})")
                    }
                }
            }
        }
    } finally {
        executor.shutdown()
    }
}

private fun writeLatexToPng(latexString: String, outputFile: File) {
    require(!outputFile.isDirectory) { "output file can't be a directory" }

    val formula = TeXFormula(latexString)
    val icon = formula.createTeXIcon(TeXConstants.STYLE_DISPLAY, 40f).apply {
        val insetSize = 50
        insets = Insets(insetSize, insetSize, insetSize, insetSize)
    }

    val bufferedImage = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB /* ARGB for transparency */)
    val graphics = bufferedImage.createGraphics().apply {
        // FIXME: This doesn't work
        val transparentBackground = true
        if (transparentBackground) {
            composite = AlphaComposite.Src
        } else {
            color = Color.white
        }
        fillRect(0, 0, icon.iconWidth, icon.iconHeight)
    }
    try {
        val jLabel = JLabel().apply { foreground = Color.black }
        icon.paintIcon(jLabel, graphics, 0, 0)
        ImageIO.write(bufferedImage, "png", outputFile)
    } finally {
        graphics.dispose()
    }
}

/**
 * Runs the given [block] on a dedicated [Thread] subject to a timeout that kills the thread. The threads created
 * by this function are daemon threads.
 *
 * This is useful for when there are blocks of code that are neither cancellable nor interrupt-friendly. The given
 * [block] should not modify anything that would require a monitor (do not call synchronized functions, modify shared
 * mutable state, do I/O operations on persistent files, etc.)
 */
suspend fun <T> withNewThreadAndTimeoutOrNull(
    timeoutMillis: Long,
    threadGroup: ThreadGroup? = null,
    threadName: String? = null,
    block: () -> T
): T? = coroutineScope {
    var thread: Thread? = null
    val deferredResult = async<T> {
        suspendCancellableCoroutine { cont ->
            thread = Thread(threadGroup) {
                cont.resume(block())
            }.apply {
                name = threadName ?: "withNewThreadAndTimeoutOrNull-${id}"
                isDaemon = true
                setUncaughtExceptionHandler { _, throwable -> cont.resumeWithException(throwable) }
                start()
            }
        }
    }

    select {
        deferredResult.onAwait { it }
        onTimeout(timeoutMillis) {
            deferredResult.cancel()
            thread?.stop()
            null
        }
    }
}
