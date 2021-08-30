package signallatexbot.core

import kotlinx.serialization.Serializable

@Serializable
data class BotConfig(
    val accountId: String,
    val outputPhotoDirectory: String,
    val avatarFilePath: String? = null
) {
    companion object {
        val EXAMPLE_CONFIG by lazy {
            BotConfig(
                accountId = "<signald account ID for the bot>",
                outputPhotoDirectory = "<output photo directory for LaTeX images>",
                avatarFilePath = "<optional path to avatar file>"
            )
        }
    }
}
