package com.tdm.app.core.engine

import com.tdm.app.core.logging.LogRepo
import com.tdm.app.core.model.TaskPriority
import com.tdm.app.core.model.TaskStatus
import com.tdm.app.data.db.DownloadTaskEntity
import com.tdm.app.data.db.SourceEntity
import com.tdm.app.data.db.SourceType
import com.tdm.app.data.db.TdmDatabase
import com.tdm.app.data.repo.SettingsRepository
import com.tdm.app.telegram.TelegramClientPort
import com.tdm.app.telegram.TgMessageInfo
import com.tdm.app.telegram.TgFileKind
import kotlinx.coroutines.flow.first

/**
 * Source Monitoring + Download Inbox (spec §4, §61, §62, §63).
 * Pipeline: new message → filter → duplicate check → create task → queue.
 * Monitoring state (lastProcessedMessageId) is persistent (spec §63).
 */
class MonitorEngine(
    private val db: TdmDatabase,
    private val settings: SettingsRepository,
    private val tg: TelegramClientPort,
) {
    private val taskDao = db.taskDao()
    private val sourceDao = db.sourceDao()

    /* ------------------------- filters (spec §61) ------------------------- */

    object FilterEngine {
        fun passes(
            filter: com.tdm.app.data.db.FileFilter,
            kind: TgFileKind,
            filename: String,
            size: Long,
        ): Boolean {
            val kindOk = when (kind) {
                TgFileKind.VIDEO -> filter.video
                TgFileKind.DOCUMENT -> filter.documents
                TgFileKind.ARCHIVE -> filter.archives
                TgFileKind.IMAGE -> filter.images
                TgFileKind.AUDIO, TgFileKind.VOICE -> filter.audio
                TgFileKind.ANIMATION -> filter.video
                TgFileKind.OTHER -> filter.documents
            }
            if (!kindOk) return false

            val ext = filename.substringAfterLast('.', "").lowercase()
            if (filter.extensions.isNotEmpty() && ext !in filter.extensions) return false
            if (filter.filenameContains.isNotBlank() &&
                !filename.contains(filter.filenameContains, ignoreCase = true)
            ) return false
            if (filter.minSizeBytes > 0 && size < filter.minSizeBytes) return false
            if (filter.maxSizeBytes < Long.MAX_VALUE && size > filter.maxSizeBytes) return false
            return true
        }

        /** Archive extensions for TgFileKind.ARCHIVE inference. */
        val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "cbz", "cbr")
        fun kindOf(kind: TgFileKind, filename: String): TgFileKind =
            if (kind == TgFileKind.DOCUMENT &&
                filename.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTS
            ) TgFileKind.ARCHIVE else kind
    }

    /* ------------------------- duplicate detection (spec §26) ------------------------- */

    suspend fun isDuplicate(chatId: Long, messageId: Long, uniqueId: String, size: Long): Boolean {
        if (taskDao.byMessage(chatId, messageId) != null) return true
        if (uniqueId.isNotBlank() && size > 0 && taskDao.byFileIdentity(uniqueId, size) != null) return true
        return false
    }

    /* ------------------------- ingestion pipeline ------------------------- */

    suspend fun onMessage(msg: TgMessageInfo) {
        val file = msg.file ?: return

        // Inbox source
        val s = settings.current()
        val inboxChatId = s.inboxChatId // 0 → Saved Messages
        val savedMessagesId = if (inboxChatId == 0L) tg.myChatId() else 0L
        val isInbox = msg.chatId == inboxChatId || (inboxChatId == 0L && savedMessagesId != 0L && msg.chatId == savedMessagesId)

        // find matching monitored source (or inbox pseudo-source)
        val source: SourceEntity? = sourceDao.byIdChat(msg.chatId)
        if (source == null && !isInbox) return
        if (source != null && (!source.enabled || !source.monitoringEnabled)) return

        val effFilter = source?.filter ?: com.tdm.app.data.db.FileFilter()
        val kind = FilterEngine.kindOf(file.kind, file.filename)
        if (!FilterEngine.passes(effFilter, kind, file.filename, file.expectedSize)) return

        if (isDuplicate(msg.chatId, msg.messageId, file.fileUniqueId, file.expectedSize)) return

        if (source != null && !source.autoDownload) {
            // spec §62: auto-download disabled → leave as DISCOVERED, user can queue manually
            createTask(msg, file, source, TaskStatus.DISCOVERED, seedQueue = false)
            return
        }

        createTask(
            msg, file, source,
            TaskStatus.QUEUED,
            seedQueue = true,
            sourceIdOverride = if (source == null) inboxSourceId() else source.id,
        )
    }

    private suspend fun inboxSourceId(): Long {
        // inbox tasks keep sourceId = 0; UI shows them under "Inbox"
        return 0L
    }

    private suspend fun createTask(
        msg: TgMessageInfo,
        file: com.tdm.app.telegram.TgFileInfo,
        source: SourceEntity?,
        status: TaskStatus,
        seedQueue: Boolean,
        sourceIdOverride: Long? = null,
    ) {
        val sourceId = sourceIdOverride ?: source?.id ?: 0L
        val entity = DownloadTaskEntity(
            sourceId = sourceId,
            accountId = source?.accountId ?: settings.current().currentAccountId,
            telegramChatId = msg.chatId,
            telegramMessageId = msg.messageId,
            topicId = source?.topicId,
            telegramFileId = file.fileId,
            telegramFileUniqueId = file.fileUniqueId,
            fileRemoteId = file.remoteId,
            size = file.expectedSize,
            filename = file.filename,
            mimeHint = file.mime,
            status = status,
            priority = source?.priority ?: TaskPriority.NORMAL,
            orderingKey = msg.messageId,
            sourceQueueMode = source?.queueMode ?: com.tdm.app.data.db.QueueMode.FIFO,
            sequentialLock = source?.queueMode in setOf(
                com.tdm.app.data.db.QueueMode.FILENAME_NATURAL,
                com.tdm.app.data.db.QueueMode.TELEGRAM_MESSAGE,
            ),
            createdAt = System.currentTimeMillis(),
            scheduleProfileId = source?.scheduleProfileId,
        )
        val id = taskDao.insert(entity)
        if (id > 0 && seedQueue) {
            LogRepo.log(db, "QUEUE", "INFO", "task created: ${file.filename} (${file.expectedSize} bytes)")
        }
    }

    /* ------------------------- scanning (spec §63) ------------------------- */

    /**
     * Scan one source. Seeds history on first use based on startFrom config, then
     * only processes messages newer than the persistent bookmark.
     */
    suspend fun scanSource(source: SourceEntity): Int {
        var processed = 0
        var lastId = source.lastProcessedMessageId
        var seeded = source.seeded

        if (!seeded) {
            val seededCount = seedInitialBacklog(source)
            sourceDao.markSeeded(source.id)
            seeded = true
            processed += seededCount
            // bookmark updated inside seedInitialBacklog
            return processed
        }

        // incremental: fetch newest-first until we hit the bookmark
        var batch = tg.recentMessages(source.chatId, 50)
        var guard = 0
        while (batch.isNotEmpty() && guard < 20) {
            guard++
            var hitBookmark = false
            for (m in batch) {
                if (m.messageId <= lastId) { hitBookmark = true; break }
                onMessage(m)
                if (m.messageId > lastId) lastId = m.messageId
                processed++
            }
            sourceDao.updateBookmark(source.id, lastId, System.currentTimeMillis())
            if (hitBookmark) break
            batch = tg.messagesBefore(source.chatId, batch.last().messageId, 50)
        }
        if (lastId > source.lastProcessedMessageId) {
            sourceDao.updateBookmark(source.id, lastId, System.currentTimeMillis())
        }
        return processed
    }

    private suspend fun seedInitialBacklog(source: SourceEntity): Int {
        var lastId = 0L
        var processed = 0
        when (source.startFromMode) {
            com.tdm.app.data.db.StartFromMode.NOW -> {
                val msgs = tg.recentMessages(source.chatId, 1)
                lastId = msgs.maxOfOrNull { it.messageId } ?: 0L
            }
            com.tdm.app.data.db.StartFromMode.LAST_N -> {
                val n = source.startFromValue.toIntOrNull() ?: 0
                var batch = tg.recentMessages(source.chatId, minOf(n, 100).coerceAtLeast(1))
                var remaining = n
                while (batch.isNotEmpty() && remaining > 0) {
                    for (m in batch) {
                        if (remaining <= 0) break
                        onMessage(m); processed++
                        lastId = maxOf(lastId, m.messageId)
                        remaining--
                    }
                    if (remaining > 0) batch = tg.messagesBefore(source.chatId, batch.last().messageId, 50)
                }
            }
            com.tdm.app.data.db.StartFromMode.SINCE_DATE -> {
                val sinceEpochSec = (source.startFromValue.toLongOrNull() ?: 0L)
                var batch = tg.recentMessages(source.chatId, 50)
                while (batch.isNotEmpty()) {
                    var stop = false
                    for (m in batch) {
                        if (m.date < sinceEpochSec) { stop = true; break }
                        onMessage(m); processed++
                        lastId = maxOf(lastId, m.messageId)
                    }
                    if (stop) break
                    batch = tg.messagesBefore(source.chatId, batch.last().messageId, 50)
                }
            }
            com.tdm.app.data.db.StartFromMode.RANGE -> {
                val parts = source.startFromValue.split('-')
                val minId = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                val maxId = parts.getOrNull(1)?.toLongOrNull() ?: Long.MAX_VALUE
                var batch = tg.recentMessages(source.chatId, 50)
                var guard = 0
                while (batch.isNotEmpty() && guard < 200) {
                    guard++
                    for (m in batch) {
                        if (m.messageId in minId..maxId) {
                            onMessage(m); processed++
                            lastId = maxOf(lastId, m.messageId)
                        }
                        if (m.messageId < minId) break
                    }
                    if (batch.last().messageId <= minId) break
                    batch = tg.messagesBefore(source.chatId, batch.last().messageId, 50)
                }
            }
            com.tdm.app.data.db.StartFromMode.ALL -> {
                var batch = tg.recentMessages(source.chatId, 100)
                var guard = 0
                while (batch.isNotEmpty() && guard < 100) {
                    guard++
                    for (m in batch) {
                        onMessage(m); processed++
                        lastId = maxOf(lastId, m.messageId)
                    }
                    batch = tg.messagesBefore(source.chatId, batch.last().messageId, 100)
                }
            }
        }
        sourceDao.updateBookmark(source.id, lastId, System.currentTimeMillis())
        return processed
    }

    /** Full pass over all monitored sources + inbox (called periodically & on demand). */
    suspend fun scanAll(): Int {
        var total = 0
        for (s in sourceDao.monitored()) {
            runCatching { total += scanSource(s) }.onFailure {
                LogRepo.log(db, "SCHEDULER", "WARN", "scan failed for ${s.name}: ${it.message}")
            }
        }
        // inbox scan
        val s = settings.current()
        val inboxChat = if (s.inboxChatId != 0L) s.inboxChatId else tg.myChatId()
        if (inboxChat != 0L) {
            val inboxSource = sourceDao.byIdChat(inboxChat)
            if (inboxSource == null) {
                // process newest messages directly through the pipeline
                val msgs = tg.recentMessages(inboxChat, 30)
                for (m in msgs) onMessage(m)
            }
        }
        return total
    }
}
