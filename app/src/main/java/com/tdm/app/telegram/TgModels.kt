package com.tdm.app.telegram

/** Telegram-side value types — decoupled from TDLib types so the adapter can change later (spec §2). */

enum class TgFileKind { VIDEO, DOCUMENT, AUDIO, IMAGE, ANIMATION, VOICE, ARCHIVE, OTHER }

data class TgFileInfo(
    val fileId: Int,
    val fileUniqueId: String,
    val remoteId: String,
    val size: Long,           // exact size if known, else 0
    val expectedSize: Long,   // best-known total size
    val filename: String,
    val mime: String,
    val kind: TgFileKind,
)

data class TgMessageInfo(
    val chatId: Long,
    val messageId: Long,
    val date: Long,           // unix seconds
    val file: TgFileInfo?,
    val text: String,
)

data class TgChat(
    val id: Long,
    val title: String,
    val type: String,         // CHANNEL | SUPERGROUP | GROUP | PRIVATE | SAVED
    val memberCount: Int,
    val username: String,
)

/** Connection state exposed by TDLib, simplified for UI/engine. */
enum class TgConnState { CONNECTING, READY, WAITING_NETWORK, UPDATING, CLOSED }

/** Authorization states surfaced to the login screen. */
sealed interface TgAuthState {
    data object Idle : TgAuthState
    data object Initializing : TgAuthState
    data object WaitingPhone : TgAuthState
    data object WaitingCode : TgAuthState
    data object WaitingPassword : TgAuthState
    data object LoggingIn : TgAuthState
    data object Ready : TgAuthState
    data object Closed : TgAuthState
    data class Failed(val message: String) : TgAuthState
}

/**
 * Error classification (spec §65): every error is classified, logged, and mapped to a
 * decision (RETRY_NOW / RETRY_DELAYED / FLOOD_WAIT / FAIL / PAUSE_ENGINE / STORAGE).
 * No catch-and-ignore anywhere in the engine.
 */
sealed class TgError(override val message: String) : Exception(message) {
    class FloodWait(val seconds: Int) : TgError("FLOOD_WAIT_$seconds")
    class Network : TgError("NETWORK_UNAVAILABLE")
    class TelegramApi(val code: Int, msg: String) : TgError("TG_$code: $msg")
    class FileNotFound : TgError("FILE_NOT_FOUND")
    class FileExpired : TgError("FILE_REFERENCE_EXPIRED")
    class AuthRequired(msg: String) : TgError("AUTH: $msg")
    class Cancelled : TgError("CANCELLED")
    class Timeout : TgError("CHUNK_TIMEOUT")
    class Unknown(cause: String) : TgError("UNKNOWN: $cause")

    enum class Decision { RETRY_NOW, RETRY_DELAYED, FLOOD_WAIT, FAIL, PAUSE_ENGINE }

    val decision: Decision
        get() = when (this) {
            is FloodWait -> Decision.FLOOD_WAIT
            is Network, is Timeout -> Decision.RETRY_NOW
            is FileExpired -> Decision.RETRY_NOW
            is FileNotFound, is Cancelled -> Decision.FAIL
            is AuthRequired -> Decision.PAUSE_ENGINE
            is TelegramApi -> when (code) {
                429 -> Decision.RETRY_DELAYED
                401 -> Decision.PAUSE_ENGINE
                else -> Decision.RETRY_DELAYED
            }
            is Unknown -> Decision.RETRY_DELAYED
        }
}

fun classifyTgError(t: Throwable): TgError = when (t) {
    is TgError -> t
    is kotlinx.coroutines.CancellationException -> TgError.Cancelled()
    else -> {
        val m = (t.message ?: t.javaClass.simpleName)
        val flood = Regex("retry after (\\d+)").find(m.lowercase())
            ?: Regex("wait for (\\d+) seconds").find(m.lowercase())
        when {
            flood != null -> TgError.FloodWait(flood.groupValues[1].toInt())
            m.contains("network", ignoreCase = true) || m.contains("connect", ignoreCase = true) -> TgError.Network()
            m.contains("timeout", ignoreCase = true) -> TgError.Timeout()
            m.contains("FILE_REFERENCE") -> TgError.FileExpired()
            m.contains("FILE_ID_INVALID") || m.contains("file not found", true) -> TgError.FileNotFound()
            else -> TgError.Unknown(m)
        }
    }
}
