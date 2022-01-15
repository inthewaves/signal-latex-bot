package signallatexbot.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
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
import kotlinx.coroutines.withContext
import org.inthewaves.kotlinsignald.Fingerprint
import org.inthewaves.kotlinsignald.Recipient
import org.inthewaves.kotlinsignald.Signal
import org.inthewaves.kotlinsignald.TrustLevel
import org.inthewaves.kotlinsignald.clientprotocol.SignaldException
import org.inthewaves.kotlinsignald.clientprotocol.v0.structures.JsonAttachment
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.Account
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.ClientMessageWrapper
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.ExceptionWrapper
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.IncomingException
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.IncomingMessage
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.JsonAddress
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.JsonQuote
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.ListenerState
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.SendResponse
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.UntrustedIdentityError
import org.inthewaves.kotlinsignald.clientprotocol.v1.structures.WebSocketConnectionState
import org.inthewaves.kotlinsignald.subscription.signalMessagesChannel
import org.scilab.forge.jlatexmath.ParseException
import signallatexbot.db.BotDatabase
import signallatexbot.db.DbWrapper
import signallatexbot.latexGenerationThreadGroup
import signallatexbot.model.LatexCiphertext
import signallatexbot.model.RequestId
import signallatexbot.model.UserIdentifier
import signallatexbot.util.AddressIdentifierCache
import signallatexbot.util.LimitedLinkedHashMap
import signallatexbot.util.addPosixPermissions
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.path.absolute
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlin.random.nextLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Any of `!tex`, `/tex`, `!latex`, `/latex` will work
 */
val GROUP_COMMAND_PREFIX_REGEX = Regex(
  """^([!/])(la)?tex[ \n](.*)""",
  setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

private const val SALT_FILENAME = "identifier-hash-salt"
private val TYPING_INDICATOR_START_DELAY_RANGE_MILLIS = 250L..500L
private val REPLY_DELAY_RANGE_MILLIS = 250L..1000L
private const val LATEX_GENERATION_TIMEOUT_MILLIS = 4000L
private const val MAX_CONCURRENT_MSG_SENDS = 4
private const val MAX_CONCURRENT_LATEX_GENERATION = 12

private const val MAX_CONCURRENT_GROUP_PROCESSING = 2

private val HISTORY_PURGE_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(1)
private const val MAX_NON_TIMED_OUT_HISTORY_LIFETIME_DAYS = 1L
private const val MAX_TIMED_OUT_HISTORY_LIFETIME_DAYS = 10L
private const val MAX_LATEX_BODY_LENGTH_CHARS = 4096
private const val MAX_LATEX_IMAGE_SIZE_BYTES = 2_048_576 // 2 MiB

private const val HARD_LIMIT_MESSAGE_COUNT_THRESHOLD_ONE_MINUTE = 15L
private const val HARD_LIMIT_MESSAGE_COUNT_THRESHOLD_TWENTY_SECONDS = 4L

private const val MAX_EXTRA_SEND_DELAY_SECONDS = 10.0

private val GROUP_REFRESH_INTERVAL = 30.seconds

class MessageProcessor(
  private val signal: Signal,
  private val outputPhotoDir: File,
  private val botConfig: BotConfig,
  private val latexGenerator: LatexGenerator,
  private val dbWrapper: DbWrapper
) : AutoCloseable {
  private val botAccountInfo: Account = signal.accountInfo ?: error("bot doesn't have account info")
  private val botUuid: String = botAccountInfo.address?.uuid ?: error("bot doesn't have UUID")

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

  private val groupSemaphoresMutex = Mutex()
  private val groupSemaphores = hashMapOf<String, Semaphore>()

  private val groupUpdateMutex = Mutex()
  private val groupIdToLastUpdateTimestampCache = LimitedLinkedHashMap<String, Duration>(maxEntries = 1000)

  private val errorHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
    val incomingMessageType = coroutineContext[IncomingMessageType]?.let { it::class.simpleName }
    val requestId: RequestId? = coroutineContext[RequestId]
    System.err.println(
      "Exception when processing incoming messages " +
        "(requestId: $requestId, incomingMessageType: $incomingMessageType): " +
        throwable.stackTraceToString()
    )
  }

  private val addressToIdentifierCache =
    AddressIdentifierCache(identifierHashSalt = identifierHashSalt, database = dbWrapper.db)

  private val processorScope = CoroutineScope(Dispatchers.IO + errorHandler)

  private fun pruneHistory() {
    dbWrapper.db.requestQueries.apply {
      val countBefore = count().executeAsOne()
      val now = System.currentTimeMillis()
      deleteNonTimedOutEntriesOlderThan(
        timestampTarget = now - TimeUnit.DAYS.toMillis(MAX_NON_TIMED_OUT_HISTORY_LIFETIME_DAYS)
      )
      deleteTimedOutEntriesOlderThan(
        timestampTarget = now - TimeUnit.DAYS.toMillis(MAX_TIMED_OUT_HISTORY_LIFETIME_DAYS)
      )
      val countNow = count().executeAsOne()
      println("pruned ${countBefore - countNow} request history entries")
    }
  }

  suspend fun runProcessor() {
    val mainJob = processorScope.launch {
      println("warning up SCrypt")
      UserIdentifier.create(JsonAddress(uuid = UUID.randomUUID().toString()), identifierHashSalt)

      trustAllUntrustedIdentityKeys(bypassTimeCheck = true)

      launch {
        while (isActive) {
          pruneHistory()
          dbWrapper.doWalCheckpointTruncate()
          delay(HISTORY_PURGE_INTERVAL_MILLIS)
        }
      }

      launch {
        while (isActive) {
          identifierMutexesMutex.withLock {
            val sizeBefore = identifierMutexes.size
            val iterator = identifierMutexes.iterator()
            for ((id, mutex) in iterator) {
              if (!mutex.isLocked) {
                iterator.remove()
                println("removed unused mutex for $id")
              }
            }
            if (identifierMutexes.size != sizeBefore) {
              println("identifierMutexes size is now ${identifierMutexes.size}")
              if (identifierMutexes.size == 0) {
                dbWrapper.doWalCheckpointTruncate()
              }
            }
          }

          groupSemaphoresMutex.withLock {
            val sizeBefore = groupSemaphores.size
            val iterator = groupSemaphores.iterator()
            for ((id, semaphore) in iterator) {
              if (semaphore.availablePermits != MAX_CONCURRENT_GROUP_PROCESSING) {
                iterator.remove()
                println("removed unused group semaphore for $id")
              }
            }
            if (groupSemaphores.size != sizeBefore) {
              println("groupSemaphores size is now ${groupSemaphores.size}")
            }
          }

          System.gc()
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
            is WebSocketConnectionState -> {
              println("Received WebSocketConnectionState update ($message)")
            }
            is ExceptionWrapper -> {
              println("Received ExceptionWrapper: (${message.data})")
              launch { handleExceptionMessage(message) }
            }
            is IncomingException -> {
              println("Received IncomingException: (${message.typedException})")
              launch { handleExceptionMessage(message) }
            }
            else -> {
              println("Received unknown message (${message::class.java.simpleName}): (${message.data})")
            }
          }
        }
      }
    }
    mainJob.join()
  }

  /**
   * A null-safe way of parsing incoming messages. This is also a [CoroutineContext.Element], meaning that the
   * [errorHandler] can figure out the request type for error logging purposes.
   */
  sealed interface IncomingMessageType : CoroutineContext.Element {
    @JvmInline
    value class LatexRequestMessage(val latexText: String) : IncomingMessageType

    @JvmInline
    value class RemoteDeleteMessage(val targetSentTimestamp: Long) : IncomingMessageType
    object InvalidMessage : IncomingMessageType

    override val key: CoroutineContext.Key<IncomingMessageType> get() = Companion

    companion object : CoroutineContext.Key<IncomingMessageType> {
      fun getHandleType(incomingMessage: IncomingMessage): IncomingMessageType {
        val remoteDelete = incomingMessage.data.dataMessage?.remoteDelete
        val body = incomingMessage.data.dataMessage?.body

        return if (remoteDelete?.targetSentTimestamp != null) {
          RemoteDeleteMessage(remoteDelete.targetSentTimestamp!!)
        } else if (!body.isNullOrBlank()) {
          if (incomingMessage.data.dataMessage?.groupV2 != null) {
            GROUP_COMMAND_PREFIX_REGEX.matchEntire(body)
              ?.groups
              ?.lastOrNull()
              ?.value
              ?.let { LatexRequestMessage(it) }
              ?: LatexRequestMessage(body)
          } else {
            LatexRequestMessage(body)
          }
        } else {
          InvalidMessage
        }
      }
    }
  }

  private val lastTrustAllAttemptTimestamp = AtomicLong(0L)
  private val trustAllMutex = Mutex()

  private suspend fun trustAllUntrustedIdentityKeys(bypassTimeCheck: Boolean = false): Unit = trustAllMutex.withLock {
    if (!bypassTimeCheck) {
      val now = System.currentTimeMillis()
      if (now < lastTrustAllAttemptTimestamp.get() + TimeUnit.MINUTES.toMillis(1)) {
        println("Not trusting identity keys --- too early")
        return
      }
      lastTrustAllAttemptTimestamp.set(now)
    }

    println("Trusting all untrusted identity keys")
    runInterruptible { signal.getAllIdentities() }
      .identityKeys
      .asSequence()
      .filter { it.address != null }
      .forEach { identityKeyList ->
        val address = identityKeyList.address!!
        identityKeyList.identities.asSequence()
          .filter { it.trustLevel == "UNTRUSTED" }
          .map { identityKey ->
            identityKey.safetyNumber?.let { Fingerprint.SafetyNumber(it) }
              ?: identityKey.qrCodeData?.let { Fingerprint.QrCodeData(it) }
          }
          .filterNotNull()
          .forEach { fingerprint ->
            // Delay because signald apparently sends out sync messages for every trust we do.
            delay(secureKotlinRandom.nextLong(300L..500L))
            try {
              runInterruptible {
                signal.trust(address, fingerprint, TrustLevel.TRUSTED_UNVERIFIED)
              }
              println("trusted an identity key for ${addressToIdentifierCache.get(address)}")
            } catch (e: SignaldException) {
              System.err.println(
                "unable to trust an identity key for ${addressToIdentifierCache.get(address)}"
              )
            }
          }
      }
  }

  private suspend fun handleExceptionMessage(message: ClientMessageWrapper) {
    val containedUntrustedIdentity = when (message) {
      is ExceptionWrapper -> {
        (message.data.message?.contains("ProtocolUntrustedIdentityException") == true)
          .also { if (it) System.err.println("ExceptionWrapper has ProtocolUntrustedIdentityException") }
      }
      is IncomingException -> {
        (message.typedException is UntrustedIdentityError)
          .also { if (it) System.err.println("IncomingException is UntrustedIdentityError") }
      }
      else -> false
    }

    if (containedUntrustedIdentity) {
      // If a user's safety number changes, their incoming messages will just be received as
      // "org.signal.libsignal.metadata.ProtocolUntrustedIdentityException" as the message. We may never be able
      // to get their actual messages until we trust their new identity key(s).
      val exception = (message as? IncomingException)?.typedException
      if (exception is UntrustedIdentityError && exception.identifier != null) {
        val identifier = exception.identifier!!
        val address = if (identifier.startsWith("+")) {
          JsonAddress(number = identifier)
        } else {
          JsonAddress(uuid = identifier)
        }

        val fingerprint: Fingerprint? = exception.identityKey
          ?.let { identityKey ->
            identityKey.safetyNumber?.let { Fingerprint.SafetyNumber(it) }
              ?: identityKey.qrCodeData?.let { Fingerprint.QrCodeData(it) }
          }
        if (fingerprint != null) {
          try {
            runInterruptible { signal.trust(address, fingerprint, TrustLevel.TRUSTED_UNVERIFIED) }
            println("trusted new key for user id ${addressToIdentifierCache.get(address)}")
          } catch (e: SignaldException) {
            System.err.println("failed to trust")
          }
          return
        }

        System.err.println("UntrustedIdentityError is missing fingerprint; falling back to trusting all keys")
      } else {
        System.err.println("UntrustedIdentityError is missing identifier; falling back to trusting all keys")
      }

      trustAllUntrustedIdentityKeys()
    }
  }

  private suspend inline fun <T> Mutex.withLockAndContext(
    coroutineContext: CoroutineContext,
    crossinline block: suspend CoroutineScope.() -> T
  ): T = withLock {
    withContext(coroutineContext) { block() }
  }

  private fun CoroutineScope.handleIncomingMessage(incomingMessage: IncomingMessage) {
    incomingMessage.data.dataMessage?.reaction?.let { jsonReaction ->
      if (jsonReaction.targetAuthor?.uuid == botAccountInfo.address?.uuid) {
        println("got a reaction to our own message")
      }
    }

    val msgId = incomingMessage.data.timestamp
    if (msgId == null) {
      println("received a message without a timestamp")
      return
    }

    val isGroupV2Message = incomingMessage.data.dataMessage?.groupV2 != null
    if (isGroupV2Message && incomingMessage.data.dataMessage?.remoteDelete == null) {
      if (incomingMessage.data.dataMessage?.body?.matches(GROUP_COMMAND_PREFIX_REGEX) != true) {
        println("received a V2 group message without a prefix ($msgId)")
        return
      }
    }

    val serverReceiveTimestamp = incomingMessage.data.serverReceiverTimestamp
    if (serverReceiveTimestamp == null) {
      println("received a message without a serverReceiveTimestamp ($msgId)")
      return
    }

    val source = incomingMessage.data.source?.takeUnless { it.uuid == null || it.number == null }
    if (source == null) {
      println("received a message without a UUID or a number as the source ($msgId)")
      return
    }

    if (source.uuid == botUuid || source.number == botAccountInfo.address?.number) {
      println("received a message to self ($msgId)")
      return
    }

    val isGroupV1Message = incomingMessage.data.dataMessage?.group != null
    if (isGroupV1Message) {
      println("received a legacy group message, which we don't send to ($msgId)")
      return
    }

    if (incomingMessage.data.dataMessage?.endSession == true) {
      println("received an end session message ($msgId)")
      return
    }

    val replyRecipient = try {
      Recipient.forReply(incomingMessage)
    } catch (e: NullPointerException) {
      println("failed to get reply recipient ($msgId)")
      return
    }

    val incomingMsgType = IncomingMessageType.getHandleType(incomingMessage)
    if (incomingMsgType is IncomingMessageType.InvalidMessage) {
      println("message is InvalidMessage ($msgId)")
      return
    }

    launch {
      // Get the identifier inside the mutex to ensure at most one identifier is generated to prevent out of
      // memory errors from concurrent SCrypt usage.
      // We use the userMutex to ensure that there is only one request per user handled at once.
      val identifier: UserIdentifier
      val userMutex: Mutex
      identifierMutexesMutex.withLock {
        identifier = addressToIdentifierCache.get(source)
        userMutex = identifierMutexes.computeIfAbsent(identifier) { Mutex() }
      }

      val maybeGroupSemaphore: NullableSemaphore = incomingMessage.data.dataMessage?.groupV2?.id?.let { groupId ->
        groupSemaphoresMutex.withLock {
          println("acquiring group semaphore for group $groupId")
          NullableSemaphore(
            groupSemaphores.computeIfAbsent(groupId) { Semaphore(permits = MAX_CONCURRENT_GROUP_PROCESSING) }
          )
        }
      } ?: NullableSemaphore(null)

      val requestId = RequestId.create(identifier, incomingMessage)

      userMutex.withLockAndContext(requestId) {
        maybeGroupSemaphore.withPermit {
          val replyMessageTimestamp by lazy { System.currentTimeMillis() }
          val latexBodyInput: String
          when (incomingMsgType) {
            IncomingMessageType.InvalidMessage -> error("unexpected message type")
            is IncomingMessageType.RemoteDeleteMessage -> {
              val targetTimestamp = incomingMsgType.targetSentTimestamp

              println("received remote delete request $requestId, targetTimestamp $targetTimestamp")
              delay(secureKotlinRandom.nextLong(REPLY_DELAY_RANGE_MILLIS))
              val timestampOfOurMsgToDelete = dbWrapper.db.requestQueries
                .getReplyTimestamp(userId = identifier, clientSentTimestamp = targetTimestamp)
                .executeAsOneOrNull()
                ?.replyMessageTimestamp
              if (timestampOfOurMsgToDelete != null) {
                sendSemaphore.withPermit {
                  runInterruptible { signal.remoteDelete(replyRecipient, timestampOfOurMsgToDelete) }
                }
                println(
                  "handled remote delete message from $requestId, " +
                    "targetTimestamp: $targetTimestamp. found the history entry"
                )
              } else {
                println(
                  "unable to handle remote delete message from $requestId, " +
                    "targetTimestamp: $targetTimestamp. can't find the history entry"
                )
              }
              return@withLockAndContext
            }
            is IncomingMessageType.LatexRequestMessage -> latexBodyInput = incomingMsgType.latexText
          }

          var timedOut = false
          var sentReply = true
          try {
            println("received LaTeX request $requestId")

            val rateLimitStatus = RateLimitStatus.getStatus(
              dbWrapper.db,
              identifier,
              incomingMsgType,
              incomingMessage,
              secureKotlinRandom
            )

            if (rateLimitStatus !is RateLimitStatus.Blocked.WithNoReply) {
              // Refresh the group's info if this is a group. This ensures group state is accurate during
              // sends. For example, if our member list copy is out of date, some members might not get our
              // message.
              incomingMessage.data.dataMessage?.groupV2?.let { groupInfo ->
                val groupId = groupInfo.id ?: return@let
                groupUpdateMutex.withLock {
                  val now = System.currentTimeMillis().milliseconds
                  val lastUpdateTime: Duration? = groupIdToLastUpdateTimestampCache[groupId]
                  val durationSinceLastUpdate = (lastUpdateTime ?: now) durationBetween now
                  if (lastUpdateTime == null || durationSinceLastUpdate >= GROUP_REFRESH_INTERVAL) {
                    println("refreshing info of groupId $groupId (durationSinceLastUpdate: $durationSinceLastUpdate)")
                    try {
                      runInterruptible { signal.getGroup(groupId) }
                    } catch (e: IOException) {
                      System.err.println(
                        "failed to refresh info of groupId $groupId: " + e.stackTraceToString()
                      )
                    }
                    groupIdToLastUpdateTimestampCache[groupId] = System.currentTimeMillis().milliseconds
                  } else {
                    val durationLeft = durationSinceLastUpdate durationBetween GROUP_REFRESH_INTERVAL
                    println(
                      "skipping refresh of info of groupId $groupId; " +
                        "too early (duration left: $durationLeft)"
                    )
                  }
                }
              }
            }

            val sendDelay: Long
            when (rateLimitStatus) {
              is RateLimitStatus.Blocked -> {
                when (rateLimitStatus) {
                  is RateLimitStatus.Blocked.WithNoReply -> {
                    println("blocking request $requestId (${rateLimitStatus.reason})")
                    sentReply = false
                  }
                  is RateLimitStatus.Blocked.WithTryAgainReply -> {
                    println(
                      "blocking request $requestId (${rateLimitStatus.reason}) and sending a try again"
                    )
                    sendReadReceipt(source, msgId)
                    sendTypingAfterDelay(replyRecipient)

                    sendMessage(
                      Reply.TryAgainMessage(
                        requestId = requestId,
                        replyRecipient = replyRecipient,
                        originalMessage = incomingMessage,
                        delay = rateLimitStatus.sendDelay,
                        replyTimestamp = replyMessageTimestamp,
                        retryAfterTimestamp = rateLimitStatus.retryAfterTimestamp,
                        currentTimestampUsed = rateLimitStatus.currentTimestampUsed
                      )
                    )
                  }
                  is RateLimitStatus.Blocked.WithRejectionReply -> {
                    println(
                      "blocking request $requestId (${rateLimitStatus.reason}) and sending back error"
                    )
                    sendReadReceipt(source, msgId)
                    sendTypingAfterDelay(replyRecipient)

                    sendMessage(
                      Reply.Error(
                        requestId = requestId,
                        replyRecipient = replyRecipient,
                        originalMessage = incomingMessage,
                        delay = rateLimitStatus.sendDelay,
                        replyTimestamp = replyMessageTimestamp,
                        errorMessage = rateLimitStatus.reason
                      )
                    )
                  }
                }
                return@withLockAndContext
              }
              is RateLimitStatus.Allowed -> {
                sendReadReceipt(source, msgId)
                sendTypingAfterDelay(replyRecipient)
                sendDelay = rateLimitStatus.sendDelay
              }
            }

            val latexImageFile = File(outputPhotoDir, "${requestId.timestamp}.png")
              .apply { deleteOnExit() }
            val latexImagePath: String? = try {
              val genMark = TimeSource.Monotonic.markNow()
              latexGenerationSemaphore.withPermit {
                println("generating LaTeX for request $requestId")
                withNewThreadAndTimeoutOrNull(
                  timeoutMillis = LATEX_GENERATION_TIMEOUT_MILLIS,
                  threadGroup = latexGenerationThreadGroup
                ) {
                  latexImageFile
                    .also { outFile -> latexGenerator.writeLatexToPng(latexBodyInput, outFile) }
                    .apply { addPosixPermissions(PosixFilePermission.GROUP_READ) }
                    .absolutePath
                }
              }.also { println("LaTeX request $requestId took ${genMark.elapsedNow()}") }
            } catch (e: OutOfMemoryError) {
              // TODO: When we switch to Podman containers, remove this since memory usage during generation
              //  will be restricted by Podman
              System.err.println("Failed to parse LaTeX for request $requestId: (OutOfMemoryError)")
              timedOut = true
              sendMessage(
                Reply.Error(
                  requestId = requestId,
                  replyRecipient = replyRecipient,
                  originalMessage = incomingMessage,
                  delay = sendDelay,
                  replyTimestamp = replyMessageTimestamp,
                  errorMessage = "Failed to parse LaTeX: Timed out"
                )
              )
              throw e
            } catch (e: Exception) {
              System.err.println("Failed to parse LaTeX for request $requestId: ${e.stackTraceToString()}")
              latexImageFile.delete()
              val errorReplyMessage = if (e is ParseException && !e.message.isNullOrBlank()) {
                "Failed to parse LaTeX: ${e.message}"
              } else {
                "Failed to parse LaTeX: Miscellaneous error"
              }

              sendMessage(
                Reply.Error(
                  requestId = requestId,
                  replyRecipient = replyRecipient,
                  originalMessage = incomingMessage,
                  delay = sendDelay,
                  replyTimestamp = replyMessageTimestamp,
                  errorMessage = errorReplyMessage
                )
              )
              // propagate coroutine cancellation
              if (e is CancellationException) throw e
              return@withLockAndContext
            }

            if (latexImagePath != null) {
              System.err.println("LaTeX request $requestId has size ${latexImageFile.length()} bytes")
              if (latexImageFile.length() <= MAX_LATEX_IMAGE_SIZE_BYTES) {
                sendMessage(
                  Reply.LatexReply(
                    requestId = requestId,
                    replyRecipient = replyRecipient,
                    originalMessage = incomingMessage,
                    delay = sendDelay,
                    replyTimestamp = replyMessageTimestamp,
                    latexImagePath = latexImagePath
                  )
                )
              } else {
                System.err.println("LaTeX request $requestId is too large")
                latexImageFile.delete()
                sendMessage(
                  Reply.Error(
                    requestId = requestId,
                    replyRecipient = replyRecipient,
                    originalMessage = incomingMessage,
                    delay = sendDelay,
                    replyTimestamp = replyMessageTimestamp,
                    errorMessage = "Failed to parse LaTeX: Resulting image is too large"
                  )
                )
              }
            } else {
              System.err.println("LaTeX request $requestId timed out")
              latexImageFile.delete()
              timedOut = true
              sendMessage(
                Reply.Error(
                  requestId = requestId,
                  replyRecipient = replyRecipient,
                  originalMessage = incomingMessage,
                  delay = sendDelay,
                  replyTimestamp = replyMessageTimestamp,
                  errorMessage = "Failed to parse LaTeX: Timed out"
                )
              )
            }
          } finally {
            dbWrapper.db.requestQueries.insert(
              userId = identifier,
              serverReceiveTimestamp = serverReceiveTimestamp,
              clientSentTimestamp = msgId,
              replyMessageTimestamp = if (sentReply) replyMessageTimestamp else null,
              timedOut = timedOut,
              // Encrypt the LaTeX for requests that took too long for abuse monitoring.
              latexCiphertext = if (timedOut) {
                LatexCiphertext.fromPlaintext(botConfig, incomingMsgType.latexText, identifier)
              } else {
                null
              }
            )
          }
        }
      }
    }
  }

  private suspend fun sendReadReceipt(source: JsonAddress, msgId: Long) {
    try {
      sendSemaphore.withPermit {
        runInterruptible { signal.markRead(source, listOf(msgId)) }
      }
    } catch (e: SignaldException) {
      System.err.println("failed to send read receipt: ${e.stackTraceToString()}")
    }
  }

  private suspend fun sendTypingAfterDelay(replyRecipient: Recipient) {
    try {
      delay(secureKotlinRandom.nextLong(TYPING_INDICATOR_START_DELAY_RANGE_MILLIS))
      sendSemaphore.withPermit {
        runInterruptible { signal.typing(replyRecipient, isTyping = true) }
      }
    } catch (e: SignaldException) {
      System.err.println("failed to send read receipt / typing indicator: ${e.stackTraceToString()}")
    }
  }

  sealed interface RateLimitStatus {
    sealed interface Blocked : RateLimitStatus {
      val reason: String

      @JvmInline
      value class WithNoReply(override val reason: String) : Blocked

      data class WithTryAgainReply(
        override val reason: String,
        val sendDelay: Long,
        val retryAfterTimestamp: Long,
        val currentTimestampUsed: Long
      ) : Blocked

      data class WithRejectionReply(
        override val reason: String,
        val sendDelay: Long,
      ) : Blocked
    }

    @JvmInline
    value class Allowed(val sendDelay: Long) : RateLimitStatus

    companion object {
      private fun calculateExtraDelayMillis(numEntriesInLastMinute: Long): Long {
        // t^2 / 25
        val extraSeconds: Double = (numEntriesInLastMinute * numEntriesInLastMinute / 25.0)
          .coerceAtMost(MAX_EXTRA_SEND_DELAY_SECONDS)
        return (extraSeconds * 1000.0).roundToLong()
          .coerceAtLeast(0L)
      }

      fun getStatus(
        database: BotDatabase,
        identifier: UserIdentifier,
        incomingMessageType: IncomingMessageType,
        incomingMessage: IncomingMessage,
        secureKotlinRandom: Random
      ): RateLimitStatus {
        val serverReceiveTimestamp = incomingMessage.data.serverReceiverTimestamp
        require(serverReceiveTimestamp != null) { "missing server receiver timestamp" }

        val entriesWithinLastMinute = database.requestQueries.requestsInInterval(
          userId = identifier,
          lowerTimestamp = serverReceiveTimestamp - TimeUnit.MINUTES.toMillis(1),
          upperTimestamp = serverReceiveTimestamp
        ).executeAsOne()
        val timedOutEntriesInLastMin = database.requestQueries.timedOutRequestsInInterval(
          userId = identifier,
          lowerTimestamp = serverReceiveTimestamp - TimeUnit.MINUTES.toMillis(1),
          upperTimestamp = serverReceiveTimestamp
        ).executeAsOne()

        val entriesInLastTwentySeconds by lazy {
          database.requestQueries.requestsInInterval(
            userId = identifier,
            lowerTimestamp = serverReceiveTimestamp - TimeUnit.SECONDS.toMillis(20),
            upperTimestamp = serverReceiveTimestamp
          ).executeAsOne()
        }

        val sendDelayRange: LongRange = if (entriesWithinLastMinute != 0L) {
          val delayAddition = calculateExtraDelayMillis(entriesWithinLastMinute)
          REPLY_DELAY_RANGE_MILLIS.let { originalRange ->
            (originalRange.first + delayAddition)..(originalRange.last + delayAddition)
          }
        } else {
          REPLY_DELAY_RANGE_MILLIS
        }
        val sendDelay = secureKotlinRandom.nextLong(sendDelayRange)

        if (timedOutEntriesInLastMin != 0L) {
          val mostRecentTimedOutTimestamp = database
            .requestQueries
            .mostRecentTimedOutRequestTimestampInInterval(
              userId = identifier,
              lowerTimestamp = serverReceiveTimestamp - TimeUnit.MINUTES.toMillis(1),
              upperTimestamp = serverReceiveTimestamp
            )
            .executeAsOne()
            .serverReceiveTimestamp!!

          return Blocked.WithTryAgainReply(
            sendDelay = sendDelay,
            reason = "sent a request that timed out within the last minute",
            retryAfterTimestamp = mostRecentTimedOutTimestamp + TimeUnit.MINUTES.toMillis(1),
            currentTimestampUsed = serverReceiveTimestamp
          )
        } else if (
          entriesWithinLastMinute >= HARD_LIMIT_MESSAGE_COUNT_THRESHOLD_ONE_MINUTE ||
          entriesInLastTwentySeconds >= HARD_LIMIT_MESSAGE_COUNT_THRESHOLD_TWENTY_SECONDS
        ) {
          val reason =
            if (entriesWithinLastMinute >= HARD_LIMIT_MESSAGE_COUNT_THRESHOLD_ONE_MINUTE) {
              "sent $entriesWithinLastMinute requests within the last minute"
            } else {
              "sent $entriesInLastTwentySeconds requests within the last 20 seconds"
            }

          // TODO: refactor rate limit intervals, or just do leaky bucket
          val isAtLimitForLastMinute =
            entriesWithinLastMinute == HARD_LIMIT_MESSAGE_COUNT_THRESHOLD_ONE_MINUTE
          val isAtLimitForTwentySeconds by lazy {
            entriesInLastTwentySeconds == HARD_LIMIT_MESSAGE_COUNT_THRESHOLD_TWENTY_SECONDS
          }

          return if (isAtLimitForLastMinute || isAtLimitForTwentySeconds) {
            val interval = if (isAtLimitForLastMinute) {
              TimeUnit.MINUTES.toMillis(1)
            } else {
              TimeUnit.SECONDS.toMillis(20)
            }
            val retryAfterTimestamp = database.requestQueries
              .leastRecentTimestampInInterval(
                userId = identifier,
                lowerTimestamp = serverReceiveTimestamp - interval,
                upperTimestamp = serverReceiveTimestamp
              )
              .executeAsOne()
              .serverReceiveTimestamp!! + interval

            Blocked.WithTryAgainReply(
              sendDelay = sendDelay,
              reason = reason,
              retryAfterTimestamp = retryAfterTimestamp,
              currentTimestampUsed = serverReceiveTimestamp
            )
          } else {
            Blocked.WithNoReply(reason)
          }
        } else {
          if (incomingMessageType is IncomingMessageType.LatexRequestMessage) {
            val body = incomingMessage.data.dataMessage?.body
              ?: return Blocked.WithRejectionReply(sendDelay = sendDelay, reason = "Missing body")
            if (body.length >= MAX_LATEX_BODY_LENGTH_CHARS) {
              return Blocked.WithRejectionReply(sendDelay = sendDelay, reason = "LaTeX is too long")
            }
          }
          return Allowed(sendDelay)
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
      println("replying to request ${reply.requestId} (${reply::class.simpleName}) after a ${reply.delay} ms delay")
      delay(reply.delay)

      fun sendErrorMessage(errorReply: Reply.ErrorReply, recipient: Recipient): SendResponse {
        println(
          "sending LaTeX request failure for ${errorReply.requestId}; " +
            "Failure message: [${errorReply.errorMessage}]"
        )
        return signal.send(
          recipient = recipient,
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

      suspend fun sendMessageToSignald(
        retryGroupMemberSubset: List<JsonAddress> = emptyList()
      ): SendResponse = runInterruptible {
        val recipient = if (reply.replyRecipient is Recipient.Group && retryGroupMemberSubset.isNotEmpty()) {
          (reply.replyRecipient as Recipient.Group).withMemberSubset(retryGroupMemberSubset)
        } else {
          reply.replyRecipient
        }

        when (reply) {
          is Reply.Error -> sendErrorMessage(reply, recipient)
          is Reply.TryAgainMessage -> sendErrorMessage(reply, recipient)
          is Reply.LatexReply -> {
            signal.send(
              recipient = recipient,
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

      val sendResponse: SendResponse = sendMessageToSignald()

      suspend fun getResultString(sendResponse: SendResponse, isRetry: Boolean) = buildString {
        val successes = sendResponse.results.count { it.success != null }
        if (sendResponse.results.size == 1) {
          if (successes == 1) {
            append("successfully handled LaTeX request ${reply.requestId}")
          } else {
            val failure = sendResponse.results.single()
            append(
              "failed to send LaTeX request ${reply.requestId}: " +
                "unregistered=${failure.unregisteredFailure}, " +
                "networkFailure=${failure.networkFailure}, " +
                "identityFailure=${failure.identityFailure != null}"
            )
          }
        } else {
          if (successes == sendResponse.results.size) {
            append("successfully handled LaTeX request ${reply.requestId} to a group")
          } else {
            append("partially sent LaTeX request ${reply.requestId} ")
            append("($successes / ${sendResponse.results.size} messages)")

            data class FailureInfo(
              val identifier: UserIdentifier?,
              val errorMessage: String,
            )

            val failureInfo: ArrayList<FailureInfo> = sendResponse.results.asSequence()
              .filter { it.success == null }
              .mapTo(ArrayList(sendResponse.results.size - successes)) { currentFailure ->
                val identifier = currentFailure.address?.let { addressToIdentifierCache.get(it) }
                val errorMessage = with(currentFailure) {
                  when {
                    networkFailure == true -> "network failure"
                    identityFailure != null -> "identity failure: $identityFailure"
                    unregisteredFailure == true -> "unregistered failure"
                    proofRequiredFailure != null -> "proof required failure: $proofRequiredFailure"
                    else -> "unknown failure"
                  }
                }
                FailureInfo(identifier, errorMessage)
              }
            append(", $failureInfo")
          }
        }
        if (isRetry) {
          append(" (retry send for identity failures)")
        }
      }
      println(getResultString(sendResponse, isRetry = false))

      val successes = sendResponse.results.count { it.success != null }
      if (successes != sendResponse.results.size) {

        println("Attempting to handle any identity failures (safety number changes)")

        data class IdentityTrustResults(val address: JsonAddress, val trustSuccessful: Boolean)

        val usersRetrustedSuccessfully: List<JsonAddress> = sendResponse.results.asFlow()
          .filter { sendResult ->
            val isValidAddress = sendResult.address?.uuid != null || sendResult.address?.number != null
            sendResult.identityFailure != null && isValidAddress
          }
          .map { it.address }
          .filterNotNull()
          .map { address ->
            val identifier = addressToIdentifierCache.get(address)
            println("trusting identities for $identifier")

            val identities = try {
              runInterruptible { signal.getIdentities(address).identities }
            } catch (e: SignaldException) {
              System.err.println("failed to get identities for address: ${e.stackTraceToString()}")
              return@map null
            }

            val identityFailuresHandled = identities.asSequence()
              .filter {
                it.trustLevel == "UNTRUSTED" && (it.safetyNumber != null || it.qrCodeData != null)
              }
              .map { identityKey ->
                identityKey.safetyNumber?.let { Fingerprint.SafetyNumber(it) }
                  ?: identityKey.qrCodeData!!.let { Fingerprint.QrCodeData(it) }
              }
              .fold(initial = 0L) { identityFailuresHandledSoFar, fingerprint ->
                try {
                  runInterruptible {
                    signal.trust(
                      address,
                      fingerprint,
                      TrustLevel.TRUSTED_UNVERIFIED
                    )
                  }
                  println("Trusted new identity key for $identifier")
                  identityFailuresHandledSoFar + 1
                } catch (e: SignaldException) {
                  System.err.println("Failed to trust $identifier: ${e.stackTraceToString()}")
                  identityFailuresHandledSoFar
                }
              }

            IdentityTrustResults(address, trustSuccessful = identityFailuresHandled > 0)
          }
          .filterNotNull()
          .filter { it.trustSuccessful }
          .map { it.address }
          .toList()

        if (usersRetrustedSuccessfully.isNotEmpty()) {
          when (reply.replyRecipient) {
            is Recipient.Group -> {
              println("retrying group message after new identity keys trusted")
              val retrySendResponse = sendMessageToSignald(
                retryGroupMemberSubset = usersRetrustedSuccessfully
              )
              println(getResultString(retrySendResponse, isRetry = true))
            }
            is Recipient.Individual -> {
              println("retrying message after new identity keys trusted")
              val retrySendResponse = sendMessageToSignald()
              println(getResultString(retrySendResponse, isRetry = true))
            }
          }
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
    processorScope.cancel("close() was called")
  }
}

@JvmInline
value class NullableSemaphore(val semaphore: Semaphore?) {
  /**
   * Executes the given action. If the [semaphore] is not null, a permit is acquired from the [semaphore] at the
   * beginning and released after the action is completed.
   */
  suspend inline fun <T> withPermit(action: () -> T) = semaphore?.withPermit { action() } ?: action()
}

infix fun Duration.durationBetween(other: Duration) = (this - other).absoluteValue

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
        name = threadName ?: "withNewThreadAndTimeoutOrNull-$id"
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
