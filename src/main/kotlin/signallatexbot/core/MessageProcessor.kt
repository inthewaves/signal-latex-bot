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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.inthewaves.kotlinsignald.Fingerprint
import org.inthewaves.kotlinsignald.Recipient
import org.inthewaves.kotlinsignald.Signal
import org.inthewaves.kotlinsignald.TrustLevel
import org.inthewaves.kotlinsignald.clientprotocol.SignaldException
import org.inthewaves.kotlinsignald.clientprotocol.v0.structures.JsonAttachment
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.ExceptionWrapper
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.IncomingMessage
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.JsonQuote
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.ListenerState
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.RemoteDelete
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.SendResponse
import org.inthewaves.kotlinsignald.subscription.signalMessagesChannel
import org.scilab.forge.jlatexmath.ParseException
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import signallatexbot.latexGenerationThreadGroup
import signallatexbot.model.RequestHistory
import signallatexbot.model.RequestId
import signallatexbot.model.UserIdentifier
import signallatexbot.util.AddressIdentifierCache
import signallatexbot.util.addPosixPermissions
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Insets
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO
import javax.swing.JLabel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.path.absolute
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlin.random.nextLong

private const val SALT_FILENAME = "identifier-hash-salt"
private val TYPING_INDICATOR_START_DELAY_RANGE_MILLIS = 250L..500L
private val REPLY_DELAY_RANGE_MILLIS = 500L..1500L
private const val LATEX_GENERATION_TIMEOUT_MILLIS = 2000L
private const val MAX_CONCURRENT_MSG_SENDS = 4
private const val MAX_CONCURRENT_LATEX_GENERATION = 12
private const val EXECUTOR_FIXED_THREAD_POOL_COUNT = MAX_CONCURRENT_LATEX_GENERATION + 2
private const val MAX_HISTORY_LIFETIME_DAYS = 10L

class MessageProcessor(
    private val signal: Signal,
    private val outputPhotoDir: File,
    private val botConfig: BotConfig
) : AutoCloseable {
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

    private val identifierMutexesMutex = Mutex()
    private val identifierMutexes = hashMapOf<UserIdentifier, Mutex>()

    private val executor = Executors.newFixedThreadPool(EXECUTOR_FIXED_THREAD_POOL_COUNT)
    private val errorHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
        System.err.println("Error occurred when processing incoming messages: ${throwable.stackTraceToString()}")
    }

    private val addressToIdentifierCache = AddressIdentifierCache(identifierHashSalt = identifierHashSalt)

    private val processorScope = CoroutineScope(executor.asCoroutineDispatcher() + errorHandler)

    private suspend fun pruneHistory() {
        println("pruning history")
        val requestHistoryFiles = RequestHistory.requestHistoryRootDir.listFiles() ?: return
        val prunedCount = AtomicLong(0)
        coroutineScope {
            requestHistoryFiles.asSequence()
                .map {
                    try {
                        Json.decodeFromString(RequestHistory.serializer(), it.readText())
                    } catch (e: IOException) {
                        System.err.println("failed to read ${it.absolutePath}: ${e.stackTraceToString()}")
                        null
                    } catch (e: SerializationException) {
                        System.err.println("failed to read ${it.absolutePath}: ${e.stackTraceToString()}")
                        null
                    }
                }
                .filterNotNull()
                .forEach { oldHistory ->
                    launch {
                        val newHistory = oldHistory.toBuilder()
                            .removeEntriesOlderThan(MAX_HISTORY_LIFETIME_DAYS, TimeUnit.DAYS)
                            .build()

                        if (newHistory != oldHistory) {
                            val entriesRemoved = (oldHistory.history.size - newHistory.history.size) +
                                    (oldHistory.timedOut.size - newHistory.timedOut.size)
                            prunedCount.addAndGet(entriesRemoved.toLong())
                        }

                        newHistory.writeToDisk()
                    }
                }
        }
        println("pruned ${prunedCount.get()} history entries")
    }

    suspend fun runProcessor() {
        val mainJob = processorScope.launch {
            pruneHistory()
            trustAllUntrustedIdentityKeys(bypassTimeCheck = true)
            launch {
                while (isActive) {
                    identifierMutexesMutex.withLock {
                        val iterator = identifierMutexes.iterator()
                        var anyRemoved = false
                        for ((id, mutex) in iterator) {
                            if (!mutex.isLocked) {
                                iterator.remove()
                                anyRemoved = true
                                println("removed unused mutex for $id")
                            }
                        }
                        if (anyRemoved) {
                            println("identifierMutexes size is now ${identifierMutexes.size}")
                        }
                    }

                    delay(TimeUnit.MINUTES.toMillis(10L))
                }
            }

            val messageChannel = signalMessagesChannel(signal)
            supervisorScope {
                messageChannel.consumeEach { message ->
                    when (message) {
                        is IncomingMessage -> handleIncomingMessage(message)
                        is ListenerState -> {
                            println("Received listener state update (connected=${message.data.connected})")
                        }
                        is ExceptionWrapper -> {
                            println("Received ExceptionWrapper: (${message.data})")
                            handleExceptionWrapperMessage(message)
                        }
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

    private val lastTrustAllAttemptTimestamp = AtomicLong(0L)
    private val trustAllMutex = Mutex()

    private suspend fun trustAllUntrustedIdentityKeys(bypassTimeCheck: Boolean = false): Unit = coroutineScope {
        trustAllMutex.withLock {
            if (bypassTimeCheck) {
                val now = System.currentTimeMillis()
                if (now < lastTrustAllAttemptTimestamp.get() + TimeUnit.MINUTES.toMillis(1)) {
                    println("Not trusting identity keys --- too early")
                    return@coroutineScope
                }
                lastTrustAllAttemptTimestamp.set(now)
            }

            val trustCallMutex = Mutex()
            println("Trusting all untrusted identity keys")
            runInterruptible { signal.getAllIdentities() }
                .identityKeys
                .asSequence()
                .filter { it.address != null }
                .forEach { identityKeyList ->
                    launch {
                        val address = identityKeyList.address!!
                        val identifier = addressToIdentifierCache.get(address)
                        identityKeyList.identities.asSequence()
                            .filter { it.trustLevel == "UNTRUSTED" }
                            .map { identityKey ->
                                identityKey.safetyNumber?.let { Fingerprint.SafetyNumber(it) }
                                    ?: identityKey.qrCodeData?.let { Fingerprint.QrCodeData(it) }
                            }
                            .filterNotNull()
                            .forEach { fingerprint ->
                                try {
                                    trustCallMutex.withLock {
                                        runInterruptible {
                                            signal.trust(
                                                address,
                                                fingerprint,
                                                TrustLevel.TRUSTED_UNVERIFIED
                                            )
                                        }
                                        println("trusted an identity key for $identifier")
                                    }
                                } catch (e: SignaldException) {
                                    System.err.println("unable to trust an identity key for $identifier")
                                }
                            }
                    }
                }
        }
    }

    private fun CoroutineScope.handleExceptionWrapperMessage(message: ExceptionWrapper) {
        launch {
            if (message.data.message?.contains("ProtocolUntrustedIdentityException") == true) {
                // If a user's safety number changes, their incoming messages will just be received as ExceptionWrapper
                // messages with "org.signal.libsignal.metadata.ProtocolUntrustedIdentityException" as the message. We
                // may never be able to get their actual messages until we trust their new identity key(s).
                //
                // Since the ExceptionWrapper message doesn't specify who the user is, we have to trust all untrusted
                // identity keys.
                trustAllUntrustedIdentityKeys()
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
            println("received a message without a UUID or a number as the source")
            return
        }

        if (source.uuid == signal.accountInfo?.address?.uuid || source.number == signal.accountInfo?.address?.number) {
            println("received a message to self")
            return
        }

        val isGroupV1Message = message.data.dataMessage?.group != null
        if (isGroupV1Message) {
            println("received a legacy group message, which we don't send to")
            return
        }

        if (message.data.dataMessage?.endSession == true) {
            println("received an end session message")
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

        val identifierDeferred = async { addressToIdentifierCache.get(source) }

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
            val identifier = identifierDeferred.await()

            // Force it so that there is only one request per user
            val userMutex = identifierMutexesMutex.withLock {
                identifierMutexes.getOrPut(identifier) { Mutex() }
            }

            userMutex.withLock {
                val existingHistoryForUser = RequestHistory.readFromFile(identifier)
                val latexBodyInput = when (msgType) {
                    is IncomingMessageType.InvalidMessage -> return@withLock
                    is IncomingMessageType.RemoteDeleteMessage -> {
                        val targetTimestamp = msgType.remoteDelete.targetSentTimestamp
                        println(
                            "handling remote delete message from ${identifier.value.take(10)}, " +
                                    "targetTimestamp: $targetTimestamp"
                        )
                        val historyEntryOfTarget = existingHistoryForUser.history
                            .asSequence<RequestHistory.BaseEntry>()
                            .plus(existingHistoryForUser.timedOut)
                            .find { it.clientSentTimestamp == targetTimestamp }
                        if (historyEntryOfTarget != null) {
                            delay(secureKotlinRandom.nextLong(REPLY_DELAY_RANGE_MILLIS))
                            sendSemaphore.withPermit {
                                runInterruptible {
                                    signal.remoteDelete(replyRecipient, historyEntryOfTarget.replyMessageTimestamp)
                                }
                            }
                        } else {
                            println(
                                "unable to handle remote delete message from ${identifier.value.take(10)}, " +
                                        "targetTimestamp: $targetTimestamp. can't find the history entry"
                            )
                        }
                        return@withLock
                    }
                    is IncomingMessageType.LatexRequestMessage -> msgType.latexText
                }


                launch {
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
                }

                val newHistoryEntry = RequestHistory.Entry(
                    clientSentTimestamp = msgId,
                    serverReceiveTime = serverReceiveTimestamp,
                    replyMessageTimestamp = System.currentTimeMillis(),
                )

                var timedOut = false
                val newHistoryBuilder = existingHistoryForUser.toBuilder()
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
                        System.err.println("LaTeX request $requestId timed out")
                        timedOut = true
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
                    if (timedOut) {
                        newHistoryBuilder.addTimedOutEntry(newHistoryEntry.toTimedOutEntry(botConfig, latexBodyInput))
                    } else {
                        newHistoryBuilder.addHistoryEntry(newHistoryEntry)
                    }
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
        try {
            val originalMsgData: IncomingMessage.Data = reply.originalMessage.data
            println("replying to request ${reply.requestId} after a ${reply.delay} ms delay")
            delay(reply.delay)

            fun sendErrorMessage(errorReply: Reply.ErrorReply): SendResponse {
                println(
                    "sending LaTeX request failure for ${errorReply.requestId}; " +
                            "Failure message: [${errorReply.errorMessage}]"
                )
                return signal.send(
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

            suspend fun sendMessage() = runInterruptible {
                when (reply) {
                    is Reply.Error -> sendErrorMessage(reply)
                    is Reply.TryAgainMessage -> sendErrorMessage(reply)
                    is Reply.LatexReply -> {
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
                    }
                }
            }

            val sendResponse: SendResponse = sendMessage()

            fun getResultString(sendResponse: SendResponse, isRetry: Boolean): String {
                val successes = sendResponse.results.count { it.success != null }
                return if (sendResponse.results.size == 1) {
                    if (successes == 1) {
                        "successfully handled LaTeX request ${reply.requestId}"
                    } else {
                        val failure = sendResponse.results.single()
                        "failed to send LaTeX request ${reply.requestId}: " +
                                "unregistered=${failure.unregisteredFailure}, " +
                                "networkFailure=${failure.networkFailure}, " +
                                "identityFailure=${failure.identityFailure != null}"
                    }
                } else {
                    if (successes == sendResponse.results.size) {
                        "successfully handled LaTeX request ${reply.requestId} to a group"
                    } else {
                        "partially sent LaTeX request ${reply.requestId} ($successes / ${sendResponse.results} messages)"
                    }
                }.let { if (isRetry) "$it (retry)" else it }
            }

            val successes = sendResponse.results.count { it.success != null }
            println(getResultString(sendResponse, isRetry = false))
            if (successes != sendResponse.results.size) {
                println("Attempting to handle identity failures (safety number changes)")
                var identityFailuresHandled = 0L
                sendResponse.results.asSequence()
                    .filter { sendResult ->
                        val isValidAddress = sendResult.address?.uuid != null || sendResult.address?.number != null
                        sendResult.identityFailure != null && isValidAddress
                    }
                    .forEach { failedIdentityResult ->
                        val address = failedIdentityResult.address!!
                        val identifier = addressToIdentifierCache.get(address)
                        println("trusting identities for $identifier")

                        val identities = try {
                            runInterruptible { signal.getIdentities(address).identities }
                        } catch (e: SignaldException) {
                            System.err.println("failed to get identities for address: ${e.stackTraceToString()}")
                            return@forEach
                        }

                        identities.asSequence()
                            .filter {
                                it.trustLevel == "UNTRUSTED" && (it.safetyNumber != null || it.qrCodeData != null)
                            }
                            .map { identityKey ->
                                identityKey.safetyNumber?.let { Fingerprint.SafetyNumber(it) }
                                    ?: identityKey.qrCodeData!!.let { Fingerprint.QrCodeData(it) }
                            }
                            .forEach { fingerprint ->
                                try {
                                    runInterruptible { signal.trust(address, fingerprint, TrustLevel.TRUSTED_UNVERIFIED) }
                                    println("Trusted new identity key for $identifier")
                                    identityFailuresHandled++
                                } catch (e: SignaldException) {
                                    System.err.println("Failed to trust $identifier: ${e.stackTraceToString()}")
                                }
                            }
                    }
                if (reply.replyRecipient !is Recipient.Group && identityFailuresHandled > 0L) {
                    println("retrying message after new identity keys trusted")
                    val retrySendResponse = sendMessage()
                    println(getResultString(retrySendResponse, isRetry = true))
                }
            }
        } finally {
            if (reply is Reply.LatexReply) {
                try {
                    Files.deleteIfExists(Paths.get(reply.latexImagePath))
                } catch (e: IOException) {
                    System.err.println("failed to delete image for request ${reply.requestId}")
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
