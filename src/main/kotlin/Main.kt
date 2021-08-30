import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
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
import signallatexbot.Hex
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Insets
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipalNotFoundException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JLabel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.asKotlinRandom
import kotlin.random.nextLong
import kotlin.system.exitProcess

val latexGenerationThreadGroup = ThreadGroup("latex-generation").apply {
    // isDaemon = true
}

private const val USAGE = "usage: signal-latex-bot accountId outputDirectory"
private val REPLY_DELAY_RANGE_MILLIS = TimeUnit.SECONDS.toMillis(1)..TimeUnit.SECONDS.toMillis(3)

sealed class SendType {
    data class LatexReply(val latexImagePath: String) : SendType()
    data class Error(val errorMessage: String) : SendType()
}

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

    println("Starting bot")

    val executor = Executors.newFixedThreadPool(12)
    try {
        runBlocking(executor.asCoroutineDispatcher()) {
            val channel = signalMessagesChannel(signal)
            val exceptionHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
                System.err.println(
                    "Uncaught exception during client image generation " +
                            "(${throwable::class.java.simpleName}): ${throwable.message}"
                )
            }
            val latexGenerationSemaphore = Semaphore(permits = 10)
            supervisorScope {
                for (msg in channel) {
                    when (msg) {
                        is IncomingMessage -> {
                            msg.data.dataMessage?.reaction?.let { jsonReaction ->
                                if (jsonReaction.targetAuthor?.uuid == signal.accountInfo!!.address?.uuid) {
                                    println("got a reaction to our own message")
                                }
                            }

                            val body = msg.data.dataMessage?.body
                            if (body == null) {
                                println("received a message without a body")
                                continue
                            }
                            val msgId = msg.data.timestamp
                            if (msgId == null) {
                                println("received a message without a timestamp")
                                continue
                            }

                            val source = msg.data.source?.takeUnless { it.uuid == null || it.number == null }
                            if (source == null) {
                                println("received a message without a UUID or a number")
                                continue
                            }
                            val replyRecipient = Recipient.forReply(msg)
                            val hashedIdentifier: String = run {
                                val identifier = source.uuid ?: source.number!!
                                val hasher = MessageDigest.getInstance("SHA-256")
                                val hashBytes = hasher.digest(identifier.encodeToByteArray())
                                Hex.encode(hashBytes)
                            }
                            println("received LaTeX request from $hashedIdentifier")
                            launch(exceptionHandler) {
                                latexGenerationSemaphore.withPermit {
                                    val random = SecureRandom().asKotlinRandom()
                                    println("handling LaTeX request from user $hashedIdentifier")
                                    val now = System.currentTimeMillis()

                                    suspend fun sendMessage(sendType: SendType) {
                                        delay(random.nextLong(REPLY_DELAY_RANGE_MILLIS))
                                        when (sendType) {
                                            is SendType.Error -> signal.send(replyRecipient, sendType.errorMessage)
                                            is SendType.LatexReply -> signal.send(
                                                recipient = replyRecipient,
                                                messageBody = "",
                                                attachments = listOf(JsonAttachment(filename = sendType.latexImagePath)),
                                                quote = JsonQuote(
                                                    id = msgId,
                                                    author = source,
                                                    text = body
                                                )
                                            )
                                        }
                                    }

                                    val latexImagePath: String? = try {
                                        withNewThreadAndTimeoutOrNull(5000L, latexGenerationThreadGroup) {
                                            val outputFile = File(outputDirectory, "$hashedIdentifier-$now.png")
                                                // .apply { deleteOnExit() }
                                            writeLatexToPng(body, outputFile)
                                            outputFile.absolutePath
                                        }
                                    } catch (e: Exception) {
                                        System.err.println(
                                            "failed to parse latex for $hashedIdentifier " +
                                                    "(${e::class.java.simpleName}): ${e.message}"
                                        )

                                        val errorMsg = if (e is ParseException && !e.message.isNullOrBlank()) {
                                            "Failed to parse LaTeX: ${e.message}"
                                        } else {
                                            "Failed to parse LaTeX: misc error"
                                        }

                                        sendMessage(SendType.Error(errorMsg))
                                        return@withPermit
                                    }
                                    if (latexImagePath == null) {
                                        delay(random.nextLong(REPLY_DELAY_RANGE_MILLIS))
                                        sendMessage(SendType.Error("Failed to parse latex: Timed out"))
                                        return@withPermit
                                    }
                                    try {
                                        delay(random.nextLong(REPLY_DELAY_RANGE_MILLIS))
                                        sendMessage(SendType.LatexReply(latexImagePath))
                                    } finally {
                                        Files.deleteIfExists(Path.of(latexImagePath))
                                    }
                                }
                            }
                        }
                        is ListenerState -> println("Received listener state update (connected=${msg.data.connected})")
                        is ExceptionWrapper -> println("Received ExceptionWrapper: (${msg.data})")
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
 * Runs the given [block] on a dedicated [Thread] subject to a timeout.
 *
 * This is useful for when there are blocks of code that are neither cancellable nor interrupt-friendly. The given
 * [block] should not modify anything that would require a monitor (do not call synchronized functions, modify shared
 * mutable state, do I/O operations on persistent files, etc.)
 */
suspend fun <T> withNewThreadAndTimeoutOrNull(
    timeoutMillis: Long,
    threadGroup: ThreadGroup? = null,
    block: () -> T?
): T? {
    var thread: Thread? = null
    try {
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { cont ->
                thread = Thread(threadGroup) {
                    cont.resume(block())
                }.apply {
                    isDaemon = true
                    setUncaughtExceptionHandler { _, throwable ->
                        cont.resumeWithException(throwable)
                    }
                    start()
                }
            }
        }
    } finally {
        thread?.stop()
    }
}
