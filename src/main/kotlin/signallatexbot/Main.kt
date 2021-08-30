package signallatexbot

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.inthewaves.kotlinsignald.Signal
import org.inthewaves.kotlinsignald.SocketUnavailableException
import org.inthewaves.kotlinsignald.clientprotocol.SignaldException
import signallatexbot.core.BotConfig
import signallatexbot.core.MessageProcessor
import signallatexbot.util.addPosixPermissions
import signallatexbot.util.changePosixGroup
import signallatexbot.util.setPosixPermissions
import java.io.File
import java.io.IOException
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipalNotFoundException
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

val latexGenerationThreadGroup = ThreadGroup("latex-generation").apply {
    // isDaemon = true
}

private const val USAGE = "usage: signal-latex-bot accountId outputDirectory"
private const val BOT_NAME = "LaTeX Bot"
private const val PRIVACY_POLICY = """
    
"""

class BotCommand : CliktCommand(name = "signal-latex-bot") {
    override fun run() {}
}

abstract class BaseSignaldCommand(name: String? = null, help: String = "") : CliktCommand(name = name, help = help) {
    private val configFile by option(
        "--config",
        help = "The config.json file to use for the bot. Defaults to config.json in the working directory"
    ).file().default(File("config.json")).validate {
        val errorMessageProvider: () -> String = {
            val prettyJson = Json { prettyPrint = true }
            "Please specify a config.json with the --config option or add a " +
                    "config.json file in the working directory with the following contents:\n" +
                    prettyJson.encodeToString(BotConfig.serializer(), BotConfig.EXAMPLE_CONFIG)
        }

        require(it.exists() && it.canRead(), errorMessageProvider)
        try {
            Json.decodeFromString(BotConfig.serializer(), it.readText())
        } catch (e: SerializationException) {
            fail("Unable to read $it: ${e.message}.\n\n" + errorMessageProvider())
        }
    }

    private val socketPath by option()

    private val socketConnectRetries by option().int().default(0)

    private fun getSignalOrThrow(accountId: String): Signal {
        var retryCount = 0
        while (true) {
            try {
                return Signal(accountId = accountId, socketPath = socketPath)
            } catch (e: IOException) {
                System.err.print("error connecting to signald socket (${e::class.java.simpleName}): ${e.message}")
                if (socketConnectRetries <= 0 || retryCount > socketConnectRetries) {
                    throw e
                }
                System.err.println("retrying in 3 seconds (retries so far: $retryCount)")
                retryCount++
                Thread.sleep(TimeUnit.SECONDS.toMillis(3))
            }
        }
    }

    final override fun run() {
        val botConfig = Json.decodeFromString(BotConfig.serializer(), configFile.readText())

        val signal = try {
            getSignalOrThrow(botConfig.accountId)
        } catch (e: SignaldException) {
            if (e is SocketUnavailableException) {
                println("failed to connect to signald: socket is unavailable: ${e.message}")
            } else {
                println("failed to connect to signald: ${e.message}")
            }
            exitProcess(1)
        }

        runAfterSignaldCheck(botConfig, signal)
    }

    abstract fun runAfterSignaldCheck(botConfig: BotConfig, signal: Signal)
}

class UpdateProfileCommand : BaseSignaldCommand(name = "update-profile", help = "Updates the profile of LaTeX bot") {
    override fun runAfterSignaldCheck(botConfig: BotConfig, signal: Signal) {

        val accountAddress = signal.accountInfo?.address ?: run {
            System.err.println("${signal.accountId} is not registered with signald")
            exitProcess(1)
        }

        val outputDir = File(botConfig.outputPhotoDirectory).also {
            check(it.exists()) { "output photo directory is missing" }
        }

        val avatarFileFromConfig = botConfig.avatarFilePath?.let { path ->
            File(path).apply {
                check(exists()) { "was given an avatar path $path in the configuration but it doesn't exist" }
                check(canRead()) { "was given an avatar path $path in the configuration but unable to read" }
            }
        }
        if (avatarFileFromConfig == null) {
            println("no avatar path given in the configuration")
        }

        val avatarCopy: File? = avatarFileFromConfig?.let { originalAvatar ->
            File(outputDir, "${accountAddress.uuid}-avatar.${originalAvatar.extension}").apply {
                deleteOnExit()
                originalAvatar.copyTo(this)
                changePosixGroup("signald")
                addPosixPermissions(PosixFilePermission.GROUP_READ)
            }
        }

        try {
            val profile = signal.getProfile(address = accountAddress)
            println("Current profile is $profile")
            println("Setting profile name to $BOT_NAME")
            signal.setProfile(
                name = BOT_NAME,
                avatarFile = avatarCopy?.absolutePath,
                about = null,
                emoji = null,
                mobileCoinAddress = null,
            )
        } finally {
            avatarCopy?.delete()
        }
    }

}

class RunCommand : BaseSignaldCommand(name = "run", help = "Runs the bot") {
    override fun runAfterSignaldCheck(botConfig: BotConfig, signal: Signal) {
        val outputDirectory = File(botConfig.outputPhotoDirectory)
            .apply { setPosixPermissions("rwxr-x---") }

        try {
            outputDirectory.changePosixGroup("signald")
        } catch (e: UserPrincipalNotFoundException) {
            println("unable to find UNIX group signald")
            exitProcess(1)
        }

        val localAccountAddress = signal.accountInfo?.address
            ?: run {
                System.err.println("Missing account info --- ${botConfig.accountId} might not be registered with signald")
                exitProcess(1)
            }

        val profile = signal.getProfile(address = localAccountAddress)
        println("Current profile for the bot is $profile --- use the update-profile command if it needs updating")

        println("Starting bot")
        runBlocking {
            MessageProcessor(signal, outputDirectory).use { messageProcessor ->
                messageProcessor.runProcessor()
            }
        }
    }

}

fun main(args: Array<String>) {
    BotCommand()
        .subcommands(RunCommand(), UpdateProfileCommand())
        .main(args)
}
