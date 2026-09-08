package com.tdm.app.telegram

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private object TdlibNativeLoader {
    @Volatile private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                System.loadLibrary("tdjni")
                loaded = true
            }
        }
    }
}

/**
 * Real TDLib implementation of [TelegramClientPort] (spec §2, §3, §57).
 * - Session persists inside app-private databaseDirectory (never shared storage — spec §3, §68).
 * - Chunked offset-based downloadFile → real byte-level resume (spec §22).
 * - FLOOD_WAIT surfaced as typed error, never bypassed (spec §41).
 */
class TdlibClient(
    private val appContext: Context,
    private val chunkTimeoutMs: Long = 120_000L,
) : TelegramClientPort {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _authState = MutableStateFlow<TgAuthState>(TgAuthState.Idle)
    override val authState: StateFlow<TgAuthState> = _authState.asStateFlow()

    private val _connState = MutableStateFlow<TgConnState>(TgConnState.CONNECTING)
    override val connState: StateFlow<TgConnState> = _connState.asStateFlow()

    private val _incoming = MutableSharedFlow<TgMessageInfo>(
        replay = 0, extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val incomingMessages: SharedFlow<TgMessageInfo> = _incoming.asSharedFlow()

    @Volatile private var client: Client? = null
    @Volatile private var apiId: Int = 0
    @Volatile private var apiHash: String = ""

    private val fileUpdates = MutableSharedFlow<TdApi.File>(
        replay = 0, extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Latest snapshot per fileId — cheap live state for progress & reconciliation. */
    private val fileStates = ConcurrentHashMap<Int, TdApi.File>()

    private var initialized = false

    /* ------------------------- lifecycle ------------------------- */

    override suspend fun init(apiId: Int, apiHash: String) {
        check(apiId != 0 && apiHash.isNotBlank()) { "api_id/api_hash required" }
        TdlibNativeLoader.ensureLoaded()
        this.apiId = apiId
        this.apiHash = apiHash
        if (initialized && client != null) return
        synchronized(this) {
            if (initialized && client != null) return
            _authState.value = TgAuthState.Initializing
            client = Client.create({ update -> routeUpdate(update) }, null) { e ->
                android.util.Log.e("TdlibClient", "TDLib unhandled exception", e)
            }
            initialized = true
        }
    }

    override fun isInitialized(): Boolean = initialized && client != null

    private fun dbDir(): String = appContext.getDir("tdlib_db", Context.MODE_PRIVATE).absolutePath
    private fun filesDir(): String = appContext.getDir("tdlib_files", Context.MODE_PRIVATE).absolutePath

    /* ------------------------- generic send ------------------------- */

    suspend fun <T : TdApi.Object> sendFn(fn: TdApi.Function<T>): T {
        val c = client ?: throw TgError.AuthRequired("TDLib not initialized")
        return suspendCancellableCoroutine { cont ->
            c.send(fn) { obj ->
                if (cont.isActive) {
                    if (obj is TdApi.Error) {
                        cont.resumeWithException(mapTdError(obj))
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        cont.resume(obj as T)
                    }
                }
            }
        }
    }

    private fun mapTdError(e: TdApi.Error): TgError {
        val msg = e.message ?: ""
        val wait = Regex("(?:retry after|wait for)\\s+(\\d+)").find(msg.lowercase())
        if (e.code == 429 || wait != null) {
            return TgError.FloodWait(wait?.groupValues?.get(1)?.toIntOrNull() ?: 30)
        }
        return when {
            msg.contains("PHONE_CODE_INVALID") || msg.contains("CODE_INVALID") -> TgError.TelegramApi(400, msg)
            msg.contains("PASSWORD_HASH_INVALID") -> TgError.TelegramApi(400, msg)
            msg.contains("SESSION") || msg.contains("AUTH_") || msg.contains("UNAUTHORIZED") ->
                TgError.AuthRequired(msg)
            msg.contains("FILE_ID_INVALID") || msg.contains("file has changed") ||
                msg.contains("file is not accessible") -> TgError.FileNotFound()
            msg.contains("FILE_REFERENCE") -> TgError.FileExpired()
            else -> TgError.TelegramApi(e.code, msg)
        }
    }

    /* ------------------------- update routing ------------------------- */

    private fun routeUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> onAuthState(update.authorizationState)
            is TdApi.UpdateConnectionState -> _connState.value = mapConn(update.state)
            is TdApi.UpdateFile -> {
                fileStates[update.file.id] = update.file
                fileUpdates.tryEmit(update.file)
            }
            is TdApi.UpdateNewMessage -> {
                val info = toMessageInfo(update.message)
                if (info != null) _incoming.tryEmit(info)
            }
            else -> Unit
        }
    }

    private fun mapConn(s: TdApi.ConnectionState): TgConnState = when (s) {
        is TdApi.ConnectionStateReady -> TgConnState.READY
        is TdApi.ConnectionStateUpdating -> TgConnState.UPDATING
        is TdApi.ConnectionStateWaitingForNetwork -> TgConnState.WAITING_NETWORK
        is TdApi.ConnectionStateConnecting,
        is TdApi.ConnectionStateConnectingToProxy -> TgConnState.CONNECTING
        else -> TgConnState.CONNECTING
    }

    private fun onAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                _authState.value = TgAuthState.Initializing
                scope.launch {
                    runCatching {
                        sendFn(
                            TdApi.SetTdlibParameters(
                                /* useTestDc = */ false,
                                /* databaseDirectory = */ dbDir(),
                                /* filesDirectory = */ filesDir(),
                                /* databaseEncryptionKey = */ ByteArray(0),
                                /* useFileDatabase = */ true,
                                /* useChatInfoDatabase = */ true,
                                /* useMessageDatabase = */ true,
                                /* useSecretChats = */ false,
                                /* apiId = */ apiId,
                                /* apiHash = */ apiHash,
                                /* systemLanguageCode = */ "en",
                                /* deviceModel = */ Build.MODEL ?: "Android",
                                /* systemVersion = */ "Android ${Build.VERSION.RELEASE}",
                                /* applicationVersion = */ "1.0.0",
                            )
                        )
                    }.onFailure { t ->
                        _authState.value = TgAuthState.Failed(classifyTgError(t).message)
                    }
                }
            }
            is TdApi.AuthorizationStateWaitEmailAddress -> {
                _authState.value = TgAuthState.Failed("This account requires email login — not supported")
            }
            is TdApi.AuthorizationStateWaitPremiumPurchase -> {
                _authState.value = TgAuthState.Ready // treat as usable session
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> _authState.value = TgAuthState.WaitingPhone
            is TdApi.AuthorizationStateWaitCode -> _authState.value = TgAuthState.WaitingCode
            is TdApi.AuthorizationStateWaitPassword -> _authState.value = TgAuthState.WaitingPassword
            is TdApi.AuthorizationStateReady -> _authState.value = TgAuthState.Ready
            is TdApi.AuthorizationStateClosing -> Unit
            is TdApi.AuthorizationStateClosed -> _authState.value = TgAuthState.Closed
            else -> Unit
        }
    }

    /* ------------------------- auth actions ------------------------- */

    override suspend fun sendPhoneNumber(phone: String) {
        sendFn(TdApi.SetAuthenticationPhoneNumber(phone.trim(), null))
    }

    override suspend fun submitCode(code: String) {
        sendFn(TdApi.CheckAuthenticationCode(code.trim()))
    }

    override suspend fun submitPassword(password: String) {
        sendFn(TdApi.CheckAuthenticationPassword(password))
    }

    override suspend fun resendCode() {
        sendFn(TdApi.ResendAuthenticationCode())
    }

    override suspend fun logOut() {
        runCatching { sendFn(TdApi.LogOut()) }
    }

    override suspend fun resolveSourceLink(input: String): TgChat? {
        return when (val link = TelegramLinkParser.parse(input)) {
            is TelegramLink.PublicChat -> searchChatByUsername(link.username)
            is TelegramLink.PublicMessage -> {
                val chat = searchChatByUsername(link.username) ?: return null
                if (message(chat.id, link.messageId) == null) return null
                chat
            }
            is TelegramLink.PrivateMessage -> {
                val chat = chatById(link.chatId) ?: return null
                if (message(chat.id, link.messageId) == null) return null
                chat
            }
            is TelegramLink.Invite -> {
                val info = runCatching {
                    sendFn<TdApi.ChatInviteLinkInfo>(TdApi.CheckChatInviteLink(link.inviteLink))
                }.getOrNull() ?: return null
                if (info.chatId == 0L) return null
                chatById(info.chatId)
            }
            null -> null
        }
    }

    override suspend fun close() {
        runCatching { sendFn(TdApi.Close()) }
    }

    /* ------------------------- chats & messages ------------------------- */

    override suspend fun myChatId(): Long =
        runCatching { sendFn<TdApi.User>(TdApi.GetMe()).id }.getOrDefault(0L)

    override suspend fun searchChatByUsername(username: String): TgChat? {
        val q = username.trim().removePrefix("@")
        if (q.isEmpty()) return null
        val chat = runCatching { sendFn<TdApi.Chat>(TdApi.SearchPublicChat(q)) }
            .getOrElse { e -> if (classifyTgError(e) is TgError.FloodWait) throw e else return null }
        return toTgChat(chat)
    }

    override suspend fun chatById(chatId: Long): TgChat? {
        val chat = runCatching { sendFn<TdApi.Chat>(TdApi.GetChat(chatId)) }.getOrNull() ?: return null
        return toTgChat(chat)
    }

    override suspend fun listDialogs(limit: Int): List<TgChat> {
        sendFn(TdApi.LoadChats(null, limit))
        val chats = sendFn(TdApi.GetChats(null, limit)).chatIds
        val out = mutableListOf<TgChat>()
        for (id in chats) {
            chatById(id)?.let { out.add(it) }
        }
        return out
    }

    override suspend fun chatTitle(chatId: Long): String =
        chatById(chatId)?.title ?: chatId.toString()

    override suspend fun recentMessages(chatId: Long, limit: Int): List<TgMessageInfo> {
        val history = sendFn(TdApi.GetChatHistory(chatId, 0L, 0, limit, false)).messages ?: return emptyList()
        return history.mapNotNull { toMessageInfo(it) }
    }

    override suspend fun messagesBefore(chatId: Long, beforeMessageId: Long, limit: Int): List<TgMessageInfo> {
        val history = sendFn(TdApi.GetChatHistory(chatId, beforeMessageId, 0, limit, false)).messages ?: return emptyList()
        return history.mapNotNull { toMessageInfo(it) }
    }

    override suspend fun message(chatId: Long, messageId: Long): TgMessageInfo? {
        val m = runCatching { sendFn<TdApi.Message>(TdApi.GetMessage(chatId, messageId)) }.getOrNull() ?: return null
        return toMessageInfo(m)
    }

    /* ------------------------- files & download ------------------------- */

    override suspend fun fileSnapshot(fileId: Int): TgFileSnapshot? {
        // prefer live cache; fall back to remote query
        fileStates[fileId]?.let { return toSnapshot(it) }
        val f = runCatching { sendFn<TdApi.File>(TdApi.GetFile(fileId)) }.getOrNull() ?: return null
        fileStates[fileId] = f
        return toSnapshot(f)
    }

    override suspend fun downloadChunk(fileId: Int, offset: Long, limit: Int, priority: Int): TgFileSnapshot {
        val target = offset + limit
        val c = client ?: throw TgError.AuthRequired("TDLib not initialized")
        val done = CompletableChunk()

        // collect updates until chunk confirmed; coalesce through fileUpdates flow
        val collector = scope.launch {
            fileUpdates.collect { f ->
                if (f.id == fileId) {
                    val done2 = f.local.isDownloadingCompleted
                    val prefix = f.local.downloadedPrefixSize
                    if (done2 || prefix >= target) done.complete(toSnapshot(f))
                }
            }
        }
        try {
            c.send(TdApi.DownloadFile(fileId, priority, offset, limit.toLong(), false)) { obj ->
                if (obj is TdApi.Error) {
                    done.fail(mapTdError(obj))
                } else if (obj is TdApi.File) {
                    fileStates[fileId] = obj
                    if (obj.local.isDownloadingCompleted || obj.local.downloadedPrefixSize >= target) {
                        done.complete(toSnapshot(obj))
                    }
                }
            }
            return withTimeout(chunkTimeoutMs) { done.await() }
        } finally {
            collector.cancel()
        }
    }

    private class CompletableChunk {
        private val latch = java.util.concurrent.CountDownLatch(1)
        @Volatile private var result: TgFileSnapshot? = null
        @Volatile private var error: TgError? = null

        fun complete(s: TgFileSnapshot) { result = s; latch.countDown() }
        fun fail(e: TgError) { error = e; latch.countDown() }

        fun await(): TgFileSnapshot {
            latch.await()
            error?.let { throw it }
            return result ?: throw TgError.Timeout()
        }
    }

    override fun cancelDownload(fileId: Int) {
        val c = client ?: return
        c.send(TdApi.CancelDownloadFile(fileId, false), null)
    }

    /* ------------------------- mapping ------------------------- */

    private fun toSnapshot(f: TdApi.File) = TgFileSnapshot(
        fileId = f.id,
        uniqueId = f.remote?.uniqueId ?: "",
        remoteId = f.remote?.id ?: "",
        size = f.size,
        expectedSize = if (f.expectedSize > 0) f.expectedSize else f.size,
        downloadedPrefixSize = f.local?.downloadedPrefixSize ?: 0,
        downloadedSize = f.local?.downloadedSize ?: 0,
        localPath = f.local?.path ?: "",
        isDownloadingActive = f.local?.isDownloadingActive ?: false,
        isDownloadingCompleted = f.local?.isDownloadingCompleted ?: false,
    )

    private fun toTgChat(c: TdApi.Chat?): TgChat? {
        c ?: return null
        val type = when (val t = c.type) {
            is TdApi.ChatTypePrivate -> "PRIVATE"
            is TdApi.ChatTypeBasicGroup -> "GROUP"
            is TdApi.ChatTypeSupergroup -> if (t.isChannel) "CHANNEL" else "SUPERGROUP"
            is TdApi.ChatTypeSecret -> "PRIVATE"
            else -> "UNKNOWN"
        }
        val title = c.title ?: ""
        return TgChat(c.id, title, type, 0, "")
    }

    private fun fileOfContent(content: TdApi.MessageContent?): TgFileInfo? = when (val c = content) {
        is TdApi.MessageDocument -> fileInfo(c.document.document, c.document.fileName, c.document.mimeType, TgFileKind.DOCUMENT)
        is TdApi.MessageVideo -> fileInfo(c.video.video, c.video.fileName, c.video.mimeType, TgFileKind.VIDEO)
        is TdApi.MessageAudio -> fileInfo(c.audio.audio, c.audio.fileName, c.audio.mimeType, TgFileKind.AUDIO)
        is TdApi.MessageAnimation -> fileInfo(c.animation.animation, c.animation.fileName, c.animation.mimeType, TgFileKind.ANIMATION)
        is TdApi.MessageVoiceNote -> fileInfo(c.voiceNote.voice, "voice_${c.voiceNote.duration}.oga", "audio/ogg", TgFileKind.VOICE)
        is TdApi.MessagePhoto -> {
            val biggest = c.photo.sizes.maxByOrNull { it.width * it.height }
            biggest?.photo?.let { fileInfo(it, "photo_${it.id}.jpg", "image/jpeg", TgFileKind.IMAGE) }
        }
        else -> null
    }

    private fun fileInfo(f: TdApi.File, name: String, mime: String, kind: TgFileKind): TgFileInfo =
        TgFileInfo(
            fileId = f.id,
            fileUniqueId = f.remote?.uniqueId ?: ("f" + f.id),
            remoteId = f.remote?.id ?: "",
            size = f.size,
            expectedSize = if (f.expectedSize > 0) f.expectedSize else f.size,
            filename = name.ifBlank { "file_${f.id}" },
            mime = mime,
            kind = kind,
        )

    private fun toMessageInfo(m: TdApi.Message?): TgMessageInfo? {
        m ?: return null
        return TgMessageInfo(
            chatId = m.chatId,
            messageId = m.id,
            date = m.date.toLong(),
            file = fileOfContent(m.content),
            text = if (m.content is TdApi.MessageText) (m.content as TdApi.MessageText).text.text else "",
        )
    }

    fun dispose() {
        scope.cancel()
    }

    companion object {
        fun setTdlibLogLevel(level: Int) {
            runCatching {
                TdlibNativeLoader.ensureLoaded()
                Client.execute(TdApi.SetLogVerbosityLevel(level))
            }
        }
    }
}
