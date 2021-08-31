package signallatexbot.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
import org.inthewaves.kotlinsignald.clientprotocol.v0.structures.JsonAttachment
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.ExceptionWrapper
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.IncomingMessage
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.JsonQuote
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.ListenerState
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.RemoteDelete
import org.inthewaves.kotlinsignald.subscription.signalMessagesChannel
import org.scilab.forge.jlatexmath.ParseException
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import signallatexbot.latexGenerationThreadGroup
import signallatexbot.model.BotIdentifier
import signallatexbot.model.RequestHistory
import signallatexbot.model.RequestId
import signallatexbot.util.LimitedLinkedHashMap
import signallatexbot.util.addPosixPermissions
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Insets
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JLabel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.path.absolute
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlin.random.nextLong
import kotlin.system.measureTimeMillis

private const val SALT_FILENAME = "identifier-hash-salt"
private val TYPING_INDICATOR_START_DELAY_RANGE_MILLIS = 250L..500L
private val REPLY_DELAY_RANGE_MILLIS = 500L..1500L
private const val LATEX_GENERATION_TIMEOUT_MILLIS = 2000L
private const val MAX_CONCURRENT_MSG_SENDS = 4
private const val MAX_CONCURRENT_LATEX_GENERATION = 12
private const val EXECUTOR_FIXED_THREAD_POOL_COUNT = MAX_CONCURRENT_LATEX_GENERATION + 2

class MessageProcessor(private val signal: Signal, private val outputPhotoDir: File) : AutoCloseable {
    private val botUuid = signal.accountInfo?.address?.uuid ?: error("bot doesn't have UUID")

    private val secureRandom = SecureRandom()
    private val secureKotlinRandom = secureRandom.asKotlinRandom()

    private val identifierHashSalt: ByteArray = run {
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

    private val latexGenerationSemaphore = Semaphore(permits = MAX_CONCURRENT_LATEX_GENERATION)
    private val identifierMutexes = hashMapOf<BotIdentifier, Mutex>()
    private val identifierMutexesMutex = Mutex()

    private val executor = Executors.newFixedThreadPool(EXECUTOR_FIXED_THREAD_POOL_COUNT)
    private val errorHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
        System.err.println("Error occurred when processing incoming messages: ${throwable.stackTraceToString()}")
    }

    private val addressToIdentifierCache = LimitedLinkedHashMap<String, BotIdentifier>(1000)

    private val processorScope = CoroutineScope(executor.asCoroutineDispatcher() + errorHandler)

    suspend fun runProcessor() {
        val mainJob = processorScope.launch {
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

            val messageChannel = signalMessagesChannel(signal)
            supervisorScope {
                messageChannel.consumeEach { message ->
                    when (message) {
                        is IncomingMessage -> handleIncomingMessage(message)
                        is ListenerState -> println("Received listener state update (connected=${message.data.connected})")
                        is ExceptionWrapper -> println("Received ExceptionWrapper: (${message.data})")
                    }
                }
            }
        }
        mainJob.join()
    }

    private sealed interface IncomingMessageType {
        @JvmInline
        value class LatexRequestMessage(val latexText: String) : IncomingMessageType
        @JvmInline
        value class RemoteDeleteMessage(val remoteDelete: RemoteDelete) : IncomingMessageType
        object InvalidMessage : IncomingMessageType

        companion object {
            fun getHandleType(incomingMessage: IncomingMessage): IncomingMessageType {
                val remoteDelete = incomingMessage.data.dataMessage?.remoteDelete
                val body = incomingMessage.data.dataMessage?.body

                return if (remoteDelete != null) {
                    RemoteDeleteMessage(remoteDelete)
                } else if (!body.isNullOrBlank()) {
                    LatexRequestMessage(body)
                } else {
                    InvalidMessage
                }
            }
        }
    }

    private fun CoroutineScope.handleIncomingMessage(message: IncomingMessage) {
        message.data.dataMessage?.reaction?.let { jsonReaction ->
            if (jsonReaction.targetAuthor?.uuid == signal.accountInfo!!.address?.uuid) {
                println("got a reaction to our own message")
            }
        }

        val msgId = message.data.timestamp
        if (msgId == null) {
            println("received a message without a timestamp")
            return
        }
        val serverReceiveTimestamp = message.data.serverReceiverTimestamp
        if (serverReceiveTimestamp == null) {
            println("received a message without a serverReceiveTimestamp")
            return
        }

        val source = message.data.source?.takeUnless { it.uuid == null || it.number == null }
        if (source == null) {
            println("received a message without a UUID or a number")
            return
        }

        val isGroupV1Message = message.data.dataMessage?.group != null
        if (isGroupV1Message) {
            println("received a legacy group message, which we don't send to")
            return
        }

        val isGroupV2Message = message.data.dataMessage?.groupV2 != null
        if (isGroupV2Message) {
            val mentionToBot = message.data.dataMessage?.mentions?.find { it.uuid == botUuid }
            if (mentionToBot == null) {
                println("received a V2 group message without a mention")
                return
            }
        }

        val replyRecipient = try {
            Recipient.forReply(message)
        } catch (e: NullPointerException) {
            println("failed to get reply recipient")
            return
        }

        val msgType = IncomingMessageType.getHandleType(message)
        if (msgType is IncomingMessageType.InvalidMessage) {
            println("message doesn't have body")
            return
        }

        val identifier = addressToIdentifierCache
            .getOrPut(BotIdentifier.getIdentifierToUse(source)) {
                val result: BotIdentifier
                val time = measureTimeMillis { result = BotIdentifier.create(source, identifierHashSalt) }
                println("generated hashed identifier in $time ms for $result due to cache miss")
                result
            }

        launch {
            try {
                sendSemaphore.withPermit {
                    runInterruptible {
                        signal.markRead(source, listOf(msgId))
                    }
                }
            } catch (e: IOException) {
                System.err.println("failed to send read receipt: ${e.stackTraceToString()}")
            }
        }

        launch {
            val userMutex = identifierMutexesMutex.withLock {
                identifierMutexes.getOrPut(identifier) { Mutex() }
            }

            userMutex.withLock {
                val existingHistoryForUser = RequestHistory.readFromFile(identifier)
                val latexBodyInput = when (msgType) {
                    is IncomingMessageType.InvalidMessage -> return@withLock
                    is IncomingMessageType.RemoteDeleteMessage -> {
                        val targetTimestamp = msgType.remoteDelete.targetSentTimestamp
                        val historyEntryOfTarget = existingHistoryForUser.history
                            .asSequence<RequestHistory.BaseEntry>()
                            .plus(existingHistoryForUser.timedOut)
                            .find { it.clientSentTimestamp == targetTimestamp }
                        if (historyEntryOfTarget != null) {
                            println(
                                "handling remote delete message from ${identifier.value.take(10)}, " +
                                        "target: $historyEntryOfTarget"
                            )
                            sendSemaphore.withPermit {
                                runInterruptible {
                                    signal.remoteDelete(replyRecipient, historyEntryOfTarget.replyMessageTimestamp)
                                }
                            }
                        } else {
                            println("unable to time ${identifier.value.take(10)}")
                        }
                        return@withLock
                    }
                    is IncomingMessageType.LatexRequestMessage -> msgType.latexText
                }

                delay(secureKotlinRandom.nextLong(TYPING_INDICATOR_START_DELAY_RANGE_MILLIS))
                launch {
                    try {
                        runInterruptible {
                            signal.typing(replyRecipient, isTyping = true)
                        }
                    } catch (e: IOException) {
                        System.err.println(
                            "failed to send typing indicator: ${e.stackTraceToString()}"
                        )
                    }
                }

                val newHistoryEntry = RequestHistory.Entry(
                    clientSentTimestamp = msgId,
                    serverReceiveTime = serverReceiveTimestamp,
                    replyMessageTimestamp = System.currentTimeMillis(),
                )

                val newHistoryBuilder = existingHistoryForUser.toBuilder().addHistoryEntry(newHistoryEntry)
                try {
                    val requestId = RequestId.create(identifier)
                    println("received LaTeX request $requestId")

                    val rateLimitStatus = RateLimitStatus.getStatus(
                        existingHistoryForUser,
                        newHistoryEntry,
                        requestId,
                        secureKotlinRandom
                    )

                    val sendDelay: Long
                    when (rateLimitStatus) {
                        is RateLimitStatus.Blocked -> {
                            when (rateLimitStatus) {
                                is RateLimitStatus.Blocked.WithNoMessage -> {
                                    println("blocking request $requestId (${rateLimitStatus.reason})")
                                }
                                is RateLimitStatus.Blocked.WithTryAgainMessage -> {
                                    println(
                                        "blocking request $requestId (${rateLimitStatus.reason}) and sending a try again"
                                    )
                                    sendMessage(
                                        Reply.TryAgainMessage(
                                            requestId = requestId,
                                            replyRecipient = replyRecipient,
                                            originalMessage = message,
                                            delay = rateLimitStatus.sendDelay,
                                            replyTimestamp = newHistoryEntry.replyMessageTimestamp,
                                            retryAfterTimestamp = rateLimitStatus.retryAfterTimestamp,
                                            currentTimestampUsed = rateLimitStatus.currentTimestampUsed
                                        )
                                    )
                                }
                                else -> System.err.println("Warning: Unexpected blocked state")
                            }
                            return@launch
                        }
                        is RateLimitStatus.SendDelayed -> {
                            sendDelay = rateLimitStatus.sendDelay
                        }
                    }

                    val latexImagePath: String? = try {
                        latexGenerationSemaphore.withPermit {
                            println("generating LaTeX for request $requestId")
                            withNewThreadAndTimeoutOrNull(
                                timeoutMillis = LATEX_GENERATION_TIMEOUT_MILLIS,
                                threadGroup = latexGenerationThreadGroup
                            ) {
                                File(outputPhotoDir, "${requestId.timestamp}.png")
                                    .apply { deleteOnExit() }
                                    .also { outFile -> writeLatexToPng(latexBodyInput, outFile) }
                                    .apply { addPosixPermissions(PosixFilePermission.GROUP_READ) }
                                    .absolutePath
                            }
                        }
                    } catch (e: Exception) {
                        System.err.println("Failed to parse LaTeX for request $requestId: ${e.stackTraceToString()}")

                        val errorMsg = if (e is ParseException && !e.message.isNullOrBlank()) {
                            "Failed to parse LaTeX: ${e.message}"
                        } else {
                            "Failed to parse LaTeX: Miscellaneous error"
                        }

                        sendMessage(
                            Reply.Error(
                                requestId = requestId,
                                replyRecipient = replyRecipient,
                                originalMessage = message,
                                delay = sendDelay,
                                replyTimestamp = newHistoryEntry.replyMessageTimestamp,
                                errorMessage = errorMsg
                            )
                        )
                        return@withLock
                    }

                    if (latexImagePath == null) {
                        newHistoryBuilder.addTimedOutEntry(newHistoryEntry.toTimedOutEntry(latexBodyInput))

                        sendMessage(
                            Reply.Error(
                                requestId = requestId,
                                replyRecipient = replyRecipient,
                                originalMessage = message,
                                delay = sendDelay,
                                replyTimestamp = newHistoryEntry.replyMessageTimestamp,
                                errorMessage = "Failed to parse LaTeX: Timed out"
                            )
                        )
                    } else {
                        sendMessage(
                            Reply.LatexReply(
                                requestId = requestId,
                                replyRecipient = replyRecipient,
                                originalMessage = message,
                                delay = sendDelay,
                                replyTimestamp = newHistoryEntry.replyMessageTimestamp,
                                latexImagePath = latexImagePath
                            )
                        )
                    }
                } finally {
                    newHistoryBuilder.build().writeToDisk()
                }
            }
        }
    }

    private fun writeLatexToPng(latexString: String, outputFile: File) {
        require(!outputFile.isDirectory) { "output file can't be a directory" }

        val formula = TeXFormula(latexString)
        val icon = formula.createTeXIcon(TeXConstants.STYLE_DISPLAY, 70f).apply {
            val insetSize = 50
            insets = Insets(insetSize, insetSize, insetSize, insetSize)
        }

        val bufferedImage = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
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

    sealed interface RateLimitStatus {
        sealed interface Blocked : RateLimitStatus {
            val reason: String

            @JvmInline
            value class WithNoMessage(override val reason: String) : Blocked

            data class WithTryAgainMessage(
                override val reason: String,
                val sendDelay: Long,
                val retryAfterTimestamp: Long,
                val currentTimestampUsed: Long
            ) : Blocked
        }

        @JvmInline
        value class SendDelayed(val sendDelay: Long) : RateLimitStatus

        companion object {
            private const val HARD_LIMIT_MESSAGE_COUNT_THRESHOLD = 10
            private val MINUTE_MILLIS = TimeUnit.MINUTES.toMillis(1)

            private const val HARD_LIMIT_MESSAGE_COUNT_THRESHOLD_TWENTY_SECONDS = 4
            private val TWENTY_SECOND_MILLIS = TimeUnit.SECONDS.toMillis(20)

            fun getStatus(
                existingHistoryForUser: RequestHistory,
                newHistoryEntry: RequestHistory.Entry,
                requestId: RequestId,
                secureKotlinRandom: Random
            ): RateLimitStatus {
                val entriesWithinLastMinute =
                    existingHistoryForUser.entriesWithinInterval(newHistoryEntry, MINUTE_MILLIS)
                        .also { entries ->
                            println(
                                "$requestId: entriesWithinLastMinute for user: " +
                                        "[${entries.joinToString { "${it.serverReceiveTime}" }}]"
                            )
                        }
                val timedOutEntriesInLastMin =
                    existingHistoryForUser.timedOutEntriesWithinInterval(newHistoryEntry, MINUTE_MILLIS)

                val entriesInLastTenSeconds =
                    existingHistoryForUser.entriesWithinInterval(newHistoryEntry, TWENTY_SECOND_MILLIS)

                val sendDelayRange: LongRange = if (entriesWithinLastMinute.size != 0) {
                    val (baseStart, baseEnd) = REPLY_DELAY_RANGE_MILLIS.let { it.first to it.last }
                    val extraSeconds = (entriesWithinLastMinute.size * entriesWithinLastMinute.size / 18.0)
                        .coerceAtMost(10.0)
                    val delayAddition = (extraSeconds * 1000L).roundToLong()
                    println("request $requestId is getting an extra $delayAddition ms of delay")
                    (baseStart + delayAddition)..(baseEnd + delayAddition)
                } else {
                    REPLY_DELAY_RANGE_MILLIS
                }
                val sendDelay = secureKotlinRandom.nextLong(sendDelayRange)

                return if (timedOutEntriesInLastMin.isNotEmpty()) {
                    Blocked.WithTryAgainMessage(
                        sendDelay = sendDelay,
                        reason = "sent a request that timed out within the last minute",
                        retryAfterTimestamp = timedOutEntriesInLastMin.last().serverReceiveTime + MINUTE_MILLIS,
                        currentTimestampUsed = newHistoryEntry.serverReceiveTime
                    )
                } else if (
                    entriesWithinLastMinute.size >= HARD_LIMIT_MESSAGE_COUNT_THRESHOLD ||
                    entriesInLastTenSeconds.size >= HARD_LIMIT_MESSAGE_COUNT_THRESHOLD_TWENTY_SECONDS
                ) {
                    val reason =
                        if (entriesInLastTenSeconds.size >= HARD_LIMIT_MESSAGE_COUNT_THRESHOLD_TWENTY_SECONDS) {
                            "sent ${entriesInLastTenSeconds.size} request within the last 10 seconds"
                        } else {
                            "sent ${entriesWithinLastMinute.size} requests within the last minute"
                        }

                    val isAtLimitForTenSeconds =
                        entriesInLastTenSeconds.size == HARD_LIMIT_MESSAGE_COUNT_THRESHOLD_TWENTY_SECONDS
                    val isAtLimitForLastMinute = entriesWithinLastMinute.size == HARD_LIMIT_MESSAGE_COUNT_THRESHOLD

                    if (isAtLimitForTenSeconds || isAtLimitForLastMinute) {
                        val retryAfterTimestamp = if (isAtLimitForTenSeconds) {
                            entriesInLastTenSeconds.first().serverReceiveTime + TWENTY_SECOND_MILLIS
                        } else {
                            entriesWithinLastMinute.first().serverReceiveTime + MINUTE_MILLIS
                        }

                        Blocked.WithTryAgainMessage(
                            sendDelay = sendDelay,
                            reason = reason,
                            retryAfterTimestamp = retryAfterTimestamp,
                            currentTimestampUsed = newHistoryEntry.serverReceiveTime
                        )
                    } else {
                        Blocked.WithNoMessage(reason)
                    }
                } else {
                    SendDelayed(sendDelay)
                }
            }
        }
    }

    private sealed interface Reply {
        val originalMessage: IncomingMessage
        val replyRecipient: Recipient
        val requestId: RequestId
        val delay: Long
        val replyTimestamp: Long

        sealed interface ErrorReply : Reply {
            val errorMessage: String
        }

        data class LatexReply(
            override val originalMessage: IncomingMessage,
            override val replyRecipient: Recipient,
            override val requestId: RequestId,
            override val delay: Long,
            override val replyTimestamp: Long,
            val latexImagePath: String
        ) : Reply

        data class Error(
            override val originalMessage: IncomingMessage,
            override val replyRecipient: Recipient,
            override val requestId: RequestId,
            override val delay: Long,
            override val replyTimestamp: Long,
            override val errorMessage: String
        ) : ErrorReply

        data class TryAgainMessage(
            override val originalMessage: IncomingMessage,
            override val replyRecipient: Recipient,
            override val requestId: RequestId,
            override val delay: Long,
            override val replyTimestamp: Long,
            private val retryAfterTimestamp: Long,
            private val currentTimestampUsed: Long,
        ) : ErrorReply {
            override val errorMessage: String
                get() {
                    val seconds = TimeUnit.MILLISECONDS.toSeconds(retryAfterTimestamp - currentTimestampUsed)
                    return if (seconds >= 4L) {
                        "Too many requests! Try again in $seconds seconds."
                    } else {
                        // don't bother showing the seconds if it's too short
                        "Too many requests! Try again."
                    }
                }
        }
    }

    private val sendSemaphore = Semaphore(permits = MAX_CONCURRENT_MSG_SENDS)

    private suspend fun sendMessage(reply: Reply): Unit = sendSemaphore.withPermit {
        val originalMsgData: IncomingMessage.Data = reply.originalMessage.data
        println("sending LaTeX request for ${reply.requestId} after a ${reply.delay} ms delay")
        delay(reply.delay)
        runInterruptible {
            fun handleError(errorReply: Reply.ErrorReply) {
                println(
                    "sending LaTeX request failure for ${errorReply.requestId}; " +
                            "Failure message: [${errorReply.errorMessage}]"
                )
                signal.send(
                    recipient = errorReply.replyRecipient,
                    messageBody = errorReply.errorMessage,
                    quote = JsonQuote(
                        id = originalMsgData.timestamp,
                        author = originalMsgData.source,
                        text = originalMsgData.dataMessage?.body,
                        mentions = originalMsgData.dataMessage?.mentions ?: emptyList()
                    ),
                    timestamp = errorReply.replyTimestamp
                )
            }

            when (reply) {
                is Reply.Error -> handleError(reply)
                is Reply.TryAgainMessage -> handleError(reply)
                is Reply.LatexReply -> {
                    try {
                        signal.send(
                            recipient = reply.replyRecipient,
                            messageBody = "",
                            attachments = listOf(JsonAttachment(filename = reply.latexImagePath)),
                            quote = JsonQuote(
                                id = originalMsgData.timestamp,
                                author = originalMsgData.source,
                                text = originalMsgData.dataMessage?.body,
                                mentions = originalMsgData.dataMessage?.mentions ?: emptyList()
                            ),
                            timestamp = reply.replyTimestamp
                        )
                    } finally {
                        Files.deleteIfExists(Path.of(reply.latexImagePath))
                    }
                }
            }
        }
    }

    override fun close() {
        executor.shutdown()
    }
}

/**
 * Runs the given [block] on a dedicated [Thread] subject to a timeout that kills the thread. The threads created
 * by this function are daemon threads.
 *
 * This is useful for when there are blocks of code that are neither cancellable nor interrupt-friendly. Due to this
 * function calling the deprecated [Thread.stop] function, the [block] should not modify anything that would require a
 * monitor (do not call synchronized functions, modify shared mutable state, do I/O operations on persistent files,
 * etc.)
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
