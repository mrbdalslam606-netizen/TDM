package com.tdm.app.telegram

import kotlinx.coroutines.flow.StateFlow

/**
 * Port between the Download Engine and any Telegram implementation (spec §2).
 * Engine code depends on this interface only — TDLib sits behind it.
 */
interface TelegramClientPort {
    val authState: StateFlow<TgAuthState>
    val connState: StateFlow<TgConnState>

    /** True once init() has created the underlying client. */
    fun isInitialized(): Boolean = false

    /** New incoming messages (monitoring pipeline consumes this). */
    val incomingMessages: kotlinx.coroutines.flow.SharedFlow<TgMessageInfo>

    /** Initialize TDLib with credentials from private storage; restores session if present. */
    suspend fun init(apiId: Int, apiHash: String)

    suspend fun sendPhoneNumber(phone: String)
    suspend fun submitCode(code: String)
    suspend fun submitPassword(password: String)
    suspend fun resendCode()
    suspend fun logOut()

    /** Chat id of "Saved Messages" (equals the user's own id in TDLib). */
    suspend fun myChatId(): Long

    suspend fun searchChatByUsername(username: String): TgChat?
    suspend fun chatById(chatId: Long): TgChat?
    suspend fun listDialogs(limit: Int = 100): List<TgChat>
    suspend fun chatTitle(chatId: Long): String

    /** Newest-first messages of a chat. */
    suspend fun recentMessages(chatId: Long, limit: Int): List<TgMessageInfo>
    /** Messages older than [beforeMessageId], newest-first. */
    suspend fun messagesBefore(chatId: Long, beforeMessageId: Long, limit: Int): List<TgMessageInfo>
    /** Single message — used to refresh stale file ids (spec §57). */
    suspend fun message(chatId: Long, messageId: Long): TgMessageInfo?

    /** Live TDLib file snapshot (partial size, local path, completion) for reconciliation. */
    suspend fun fileSnapshot(fileId: Int): TgFileSnapshot?

    /**
     * Download one contiguous chunk starting at [offset].
     * Suspends until the chunk is confirmed by TDLib (updateFile) or fails.
     * This is the primitive the engine's rate limiter wraps (spec §11, §57).
     */
    suspend fun downloadChunk(fileId: Int, offset: Long, limit: Int, priority: Int = 16): TgFileSnapshot

    /** Cancel any in-flight TDLib download for [fileId]. */
    fun cancelDownload(fileId: Int)

    suspend fun close()
}

/** Neutral snapshot of TDLib File state. */
data class TgFileSnapshot(
    val fileId: Int,
    val uniqueId: String,
    val remoteId: String,
    val size: Long,
    val expectedSize: Long,
    val downloadedPrefixSize: Long,
    val downloadedSize: Long,
    val localPath: String,
    val isDownloadingActive: Boolean,
    val isDownloadingCompleted: Boolean,
)
