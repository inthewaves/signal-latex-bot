import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Insets
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.JLabel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class TexGenerationRequest(
    val continuation: CancellableContinuation<File>
)

val latexGenerationThreadGroup = ThreadGroup("latex-generation").apply {
    // isDaemon = true
}

/**
 * Runs the given [block] on a dedicated [Thread] subject to a timeout.
 *
 * This is useful for when there are blocks of code that are neither cancellable nor interrupt-friendly. The given
 * [block] should not modify anything that would require a monitor (do not call synchronized functions, modify shared
 * mutable state, etc.)
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

    // use a supervisorScope to prevent the failure of the deferred from throwing a CancellationException
    /*
    return supervisorScope {
        var thread: Thread? = null
        val deferredRunResult: Deferred<T?> = async {
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
        val timeoutJob: Job = launch { delay(timeoutMillis) }
        try {
            select {
                deferredRunResult.onAwait { it }
                timeoutJob.onJoin {
                    println("timed out at ${Throwable().stackTraceToString()}")
                    null
                }
            }
        } finally {
            println("Finally!")
            thread?.stop()
        }
    }

     */
}

@OptIn(ObsoleteCoroutinesApi::class)
fun main(args: Array<String>) {
    println("Hello World!")

    runBlocking {

        val actor = actor<TexGenerationRequest> {
            val generationSemaphore = Semaphore(permits = 5)
            for (request in channel) {
                launch {
                    generationSemaphore.withPermit {
                        val icon = AtomicReference<File?>(null)
                        val thread = Thread {
                            println("Hello from thread ${Thread.currentThread().id}")
                            Thread.sleep(500L)
                        }
                        thread.start()

                    }
                }
            }
        }

        actor.close()

        launch(Dispatchers.IO) {
            println("Doing timeout at ${System.currentTimeMillis()}")

            try {
                withNewThreadAndTimeoutOrNull(timeoutMillis = 5000L, threadGroup = latexGenerationThreadGroup) {
                    throw IOException("HI")
                }
                // async<String> { throw IOException("HI") }.await()
            } catch (e: IOException) {
                println("CAUGHT IOEXCEPTION: ${e.message}")
            }

            println("Doing next timeout at ${System.currentTimeMillis()}")

            val result = withNewThreadAndTimeoutOrNull<String>(timeoutMillis = 2000L) {
                var lastTimePrinted = System.nanoTime()
                var counter = 1
                while (true) {
                    counter++
                    // if (counter < 3) break
                    val now = System.nanoTime()
                    if (now - lastTimePrinted >= TimeUnit.SECONDS.toNanos(1L)) {
                        lastTimePrinted = now
                        println("Hello from thread ${Thread.currentThread().name}, I am looping forever")
                    }
                }
                "Hey"
            }
            println("result is $result")
            println("Dpne timeout at ${System.currentTimeMillis()}")
        }
    }

    // Try adding program arguments at Run/Debug configuration
    println("Program arguments: ${args.joinToString()}")

    val latexText = args.joinToString(separator = " ")
    if (args.isEmpty()) {
        println("please enter latex as args")
        return
    }
    println("Processing latex [$latexText]")

    val formula = TeXFormula(latexText)
    val icon = formula.createTeXIcon(TeXConstants.STYLE_DISPLAY, 40f)
    val insetSize = 50
    icon.insets = Insets(insetSize, insetSize, insetSize, insetSize)

    val transparentBackground = true

    val bufferedImage = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB /* ARGB for transparency */)
    val graphics = bufferedImage.createGraphics().apply {
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

        ImageIO.write(bufferedImage, "png", File("./formula.png"))
    } finally {
        graphics.dispose()
    }

    TimeUnit.SECONDS.sleep(3L)
}