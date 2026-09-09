package com.tdm.app.telegram

import android.content.Context
import com.tdm.app.data.db.AccountEntity
import com.tdm.app.data.db.TdmDatabase
import com.tdm.app.data.repo.SettingsRepository
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Routes Telegram operations to the selected account without sharing TDLib directories. */
class TelegramAccountManager(
    private val context: Context,
    private val database: TdmDatabase,
    private val settings: SettingsRepository,
) : TelegramClientPort {
    private val clients = mutableMapOf<String, TdlibClient>()
    @Volatile private var activeId: String = "legacy"

    private suspend fun activeAccount(): AccountEntity? {
        val s = settings.current()
        return database.accountDao().byId(s.currentAccountId)
    }

    private suspend fun activeClient(): TdlibClient {
        val s = settings.current()
        val account = activeAccount()
            ?: if (s.apiId > 0 && s.apiHash.isNotBlank()) {
                val id = "legacy-${s.apiId}"
                val created = AccountEntity(id, "Telegram", s.phoneNumberHint, s.apiId, s.apiHash, s.loggedIn)
                database.accountDao().upsert(created)
                settings.update { it.copy(currentAccountId = id) }
                created
            } else throw IllegalStateException("No Telegram account selected")
        activeId = account.id
        return clients.getOrPut(account.id) { TdlibClient(context, account.id) }
    }

    suspend fun accounts(): List<AccountEntity> = database.accountDao().observeAllOnce()

    suspend fun switchAccount(accountId: String) {
        check(database.accountDao().byId(accountId) != null) { "Account not found" }
        settings.update { it.copy(currentAccountId = accountId) }
        activeId = accountId
        database.accountDao().markUsed(accountId)
    }

    suspend fun removeAccount(accountId: String) {
        clients.remove(accountId)?.close()
        context.getDir("accounts", Context.MODE_PRIVATE).resolve(accountId).deleteRecursively()
        database.accountDao().delete(accountId)
        val remaining = database.accountDao().observeAllOnce()
        val next = remaining.firstOrNull()
        activeId = next?.id ?: "legacy"
        settings.update { it.copy(currentAccountId = next?.id ?: "legacy", loggedIn = next?.loggedIn == true) }
        if (next?.loggedIn == true) {
            runCatching { clients.getOrPut(next.id) { TdlibClient(context, next.id) }.init(next.apiId, next.apiHash) }
        }
    }

    suspend fun logoutCurrentAndRemove() {
        val id = settings.current().currentAccountId
        if (id == "legacy") {
            runCatching { activeClient().logOut() }
            settings.clearTelegramCredentials()
            activeId = "legacy"
            return
        }
        runCatching { clients[id]?.logOut() }
        removeAccount(id)
    }

    override val authState: StateFlow<TgAuthState> get() = activeState().authState
    override val connState: StateFlow<TgConnState> get() = activeState().connState
    override val incomingMessages: SharedFlow<TgMessageInfo> get() = activeState().incomingMessages

    private fun activeState(): TdlibClient {
        return clients.getOrPut(activeId) { TdlibClient(context, activeId) }
    }

    override fun isInitialized(): Boolean = activeState().isInitialized()

    override suspend fun init(apiId: Int, apiHash: String) {
        val s = settings.current()
        var id = s.currentAccountId
        if (id == "legacy" || database.accountDao().byId(id) == null) {
            id = "acct-${apiId}-${apiHash.take(8)}"
            database.accountDao().upsert(AccountEntity(id, "Telegram", s.phoneNumberHint, apiId, apiHash))
            settings.update { it.copy(currentAccountId = id) }
        }
        activeId = id
        val client = clients.getOrPut(id) { TdlibClient(context, id) }
        client.init(apiId, apiHash)
    }

    override suspend fun sendPhoneNumber(phone: String) = activeClient().sendPhoneNumber(phone)
    override suspend fun submitCode(code: String) = activeClient().submitCode(code)
    override suspend fun submitPassword(password: String) = activeClient().submitPassword(password)
    override suspend fun resendCode() = activeClient().resendCode()
    override suspend fun logOut() = activeClient().logOut()
    override suspend fun resolveSourceLink(input: String) = activeClient().resolveSourceLink(input)
    override suspend fun resolveTelegramLink(input: String) = activeClient().resolveTelegramLink(input)
    override suspend fun myChatId() = activeClient().myChatId()
    override suspend fun searchChatByUsername(username: String) = activeClient().searchChatByUsername(username)
    override suspend fun chatById(chatId: Long) = activeClient().chatById(chatId)
    override suspend fun listDialogs(limit: Int) = activeClient().listDialogs(limit)
    override suspend fun chatTitle(chatId: Long) = activeClient().chatTitle(chatId)
    override suspend fun recentMessages(chatId: Long, limit: Int) = activeClient().recentMessages(chatId, limit)
    override suspend fun messagesBefore(chatId: Long, beforeMessageId: Long, limit: Int) = activeClient().messagesBefore(chatId, beforeMessageId, limit)
    override suspend fun message(chatId: Long, messageId: Long) = activeClient().message(chatId, messageId)
    override suspend fun fileSnapshot(fileId: Int) = activeClient().fileSnapshot(fileId)
    override suspend fun downloadChunk(fileId: Int, offset: Long, limit: Int, priority: Int) = activeClient().downloadChunk(fileId, offset, limit, priority)
    override fun cancelDownload(fileId: Int) = activeState().cancelDownload(fileId)
    override suspend fun close() = activeClient().close()
}
