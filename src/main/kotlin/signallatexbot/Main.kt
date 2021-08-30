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
import signallatexbot.core.MessageProcessor
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
    runBlocking {
        MessageProcessor(signal, outputDirectory).use { messageProcessor ->
            messageProcessor.runProcessor()
        }
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
