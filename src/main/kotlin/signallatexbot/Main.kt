package signallatexbot

import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.output.TermUi
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.OptionValidator
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.JsonKeysetReader
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HybridConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.inthewaves.kotlinsignald.Signal
import org.inthewaves.kotlinsignald.SocketUnavailableException
import org.inthewaves.kotlinsignald.clientprotocol.SignaldException
import signallatexbot.core.BotConfig
import signallatexbot.core.JLaTeXMathGenerator
import signallatexbot.core.MessageProcessor
import signallatexbot.db.executeAsSequence
import signallatexbot.db.withDatabase
import signallatexbot.model.UserIdentifier
import signallatexbot.serialization.KeysetHandlePlaintextJsonSerializer
import signallatexbot.util.addPosixPermissions
import signallatexbot.util.changePosixGroup
import signallatexbot.util.setPosixPermissions
import java.io.File
import java.io.IOException
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipalNotFoundException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import kotlin.system.exitProcess

val latexGenerationThreadGroup = ThreadGroup("latex-generation").apply {
    // isDaemon = true
}

private const val BOT_NAME = "LaTeX Bot"
private const val PRIVACY_POLICY = """
    
"""

class BotCommand : CliktCommand(name = "signal-latex-bot") {
    override fun run() {}
}

class DecryptHistoryCommand : CliktCommand(name = "decrypt-history") {
    private val privateKeysetFile by argument().file(mustBeReadable = true)
    private val databasePath by argument().file(mustBeReadable = true)
    private val identifier by argument()

    override fun run() {
        val userId = UserIdentifier(identifier)
        val privateKeyset = CleartextKeysetHandle.read(JsonKeysetReader.withFile(privateKeysetFile))

        println("Decrypted history for identifier $userId: ")
        runBlocking {
            withDatabase(path = databasePath.absolutePath) { db ->
                db.requestQueries
                    .getRequestsWithLatexCiphertext(userId)
                    .executeAsSequence { sequence ->
                        sequence.forEach {
                            fun formatDate(timestamp: Long?): String? =
                                timestamp
                                    ?.let { Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()) }
                                    ?.toString()

                            println(
                                "History entry with " +
                                        "replyMessageTimestamp=${it.replyMessageTimestamp} " +
                                        "(${formatDate(it.replyMessageTimestamp)}), " +
                                        "serverReceiveTimestamp=${it.serverReceiveTimestamp} " +
                                        "(${formatDate(it.serverReceiveTimestamp)}) " +
                                        "has LaTeX:\n" +
                                        it.latexCiphertext.decrypt(privateKeyset, userId).decodeToString() +
                                        "\n================================="
                            )
                        }
                    }
            }
        }
    }
}

abstract class BaseSignaldCommand(name: String? = null, help: String = "") : CliktCommand(name = name, help = help) {
    private val configFile by option(
        "--config",
        help = "The config.json file to use for the bot. Defaults to config.json in the working directory"
    ).file().default(File("config.json")).validate(configFileValidator)

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

private val configFileValidator: OptionValidator<File> = {
    fun getErrorMessage() {
        "Please specify a config.json with the --config option or add a " +
                "config.json file in the working directory with the following contents:\n" +
                BotConfig.EXAMPLE_CONFIG
    }

    if (it.exists()) {
        require(it.canRead()) { "${it.absolutePath} exists but can't read it. ${getErrorMessage()}" }
    }
}

class UpdateConfigCommand : CliktCommand(
    name = "update-config",
    help = "A utility command to update the configuration file for the bot via prompts."
) {
    private val configFile by option(
        "--config",
        help = "The config.json file to use for the bot. Defaults to config.json in the working directory"
    ).file().default(File("config.json")).validate(configFileValidator)

    private val isLocal by option(
        "--local", "-l",
        help = "Set this flag if creating a configuration file locally for uploading to the server later"
    ).flag()

    override fun run() {
        TermUi.echo("Beginning configuration update / creation")
        if (isLocal) {
            TermUi.echo("""
                This configuration file will be created locally. You are expected to upload the final config.json
                file to the server afterwards.
            """.trimIndent())
        }

        val existingConfig: BotConfig? = if (!configFile.exists()) {
            null
        } else {
            try {
                Json.decodeFromString(BotConfig.serializer(), configFile.readText())
            } catch (e: SerializationException) {
                TermUi.echo(
                    "${configFile.absolutePath} already exists but is not a signal-latex-bot " +
                            "configuration.\nError: ${e.message}\nContents: ${configFile.readText().take(12894)}"
                )
                val confirmResult = TermUi.confirm("Overwrite it?", abort = true)
                if (confirmResult != true) {
                    exitProcess(1)
                }
                null
            }
        }

        val intro = if (existingConfig != null) {
            "Editing existing config.json file"
        } else {
            "You need to create a config.json file"
        }
        TermUi.echo(intro)

        val accountId = TermUi.prompt(
            if (isLocal) {
                "Enter a signald account id registered on the remote server"
            } else {
                "Enter a signald account id"
            },
            default = existingConfig?.accountId
        ) ?: throw UsageError("Missing accountId")

        if (!isLocal) {
            val signal = try {
                Signal(accountId)
            } catch (e: SignaldException) {
                System.err.println(
                    "Unable to connect to signald (${e::class.java.simpleName}: ${e.message}. If generating a config " +
                            "file on your local machine, re-run the command with the --local flag."
                )
                throw Abort()
            }
            if (!signal.isRegisteredWithSignald) {
                System.err.println(
                    "$accountId is not registered with signald. If generating a config file on your " +
                        "local machine, re-run the command with the --local flag."
                )
                throw Abort()
            }
        }

        val outputPhotoDirectory = TermUi.prompt(
            if (isLocal) {
                "Enter an output photo directory on the remote server for LaTeX photos"
            } else {
                "Enter an output photo directory for LaTeX photos"
            },
            default = existingConfig?.outputPhotoDirectory
        ) {
            File(it).apply {
                if (!exists() && !mkdirs()) {
                    if (!isLocal) throw UsageError("Directory $this doesn't exist and can't make the directory")
                }
            }
        } ?: throw UsageError("Missing output photo directory")


        val nonePath = "_!!NONE"
        val avatarFilePath = TermUi.prompt(
            if (isLocal) {
                "Enter an avatar file path, where the path is located on the server (optional)"
            } else {
                "Enter an avatar file path (optional)"
            },
            default = existingConfig?.avatarFilePath ?: nonePath,
            showDefault = existingConfig?.avatarFilePath != null
        ) {
            if (it != nonePath) {
                val actualPath =
                    it.replaceFirst(Regex("^~"), Matcher.quoteReplacement(System.getProperty("user.home")))

                if (isLocal && actualPath != it) {
                    System.err.println(
                        "Warning: Using ~ in paths won't get translated when the configuration is " +
                                "copied up to the server"
                    )
                }

                File(actualPath).apply {
                    if (!exists()) {
                        if (!isLocal) throw UsageError("Avatar $this doesn't exist")
                    }
                }
            } else {
                null
            }
        }

        val publicKeysetHandle: KeysetHandle = if (
            existingConfig?.publicKeysetHandle == null ||
            TermUi.confirm("Replace public key with a new one?") == true
        ) {
            val privateKeyset = BotConfig.generatePrivateKeysetHandle()
            val serializedPrivateKeyset = KeysetHandlePlaintextJsonSerializer.serializeKeysetHandle(privateKeyset)
            if (isLocal) {
                val pwd = Paths
                    .get(Paths.get(".").toAbsolutePath().normalize().toString(), "private-keyset.json")
                    .toString()
                val privateKeysetFile =
                    TermUi.prompt(
                        "Enter a path on your local machine to write the private keyset file",
                        default = pwd
                    ) {
                        val actualPath =
                            it.replaceFirst(Regex("^~"), Matcher.quoteReplacement(System.getProperty("user.home")))
                        File(actualPath)
                    } ?: throw Abort()

                if (
                    privateKeysetFile.exists() &&
                    TermUi.confirm(
                        text = "${privateKeysetFile.absolutePath} already exists. Overwrite?",
                        abort = true
                    ) != true
                ) {
                    throw Abort()
                }
                privateKeysetFile.writeText(serializedPrivateKeyset)
                TermUi.echo("Wrote the private keyset to ${privateKeysetFile.absolutePath}")
            } else {
                TermUi.echo("Private keyset is ")
                TermUi.echo(serializedPrivateKeyset)
                TermUi.echo("Please save the private keyset into a file for decrypting history when needed")
            }
            privateKeyset.publicKeysetHandle
        } else {
            existingConfig.publicKeysetHandle
        }

        BotConfig(
            accountId = accountId,
            outputPhotoDirectory = outputPhotoDirectory.absolutePath,
            publicKeysetHandle = publicKeysetHandle,
            avatarFilePath = avatarFilePath?.absolutePath
        ).also { newConfig ->
            val prettyJson = Json { prettyPrint = true }
            val configurationAsString = prettyJson.encodeToString(BotConfig.serializer(), newConfig)
            println("Successfully created configuration")
            println(configurationAsString)
            println("Writing it to ${configFile.absolutePath}")
            configFile.writeText(configurationAsString)
        }
    }
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
            withDatabase { database ->
                MessageProcessor(
                    signal = signal,
                    outputPhotoDir = outputDirectory,
                    botConfig = botConfig,
                    latexGenerator = JLaTeXMathGenerator(),
                    database = database
                ).use { messageProcessor -> messageProcessor.runProcessor() }
            }

        }
    }

}

fun main(args: Array<String>) {
    HybridConfig.register()

    BotCommand()
        .subcommands(RunCommand(), UpdateProfileCommand(), UpdateConfigCommand(), DecryptHistoryCommand())
        .main(args)
}
