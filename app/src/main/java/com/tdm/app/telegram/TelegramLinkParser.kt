package com.tdm.app.telegram

/** Parsed, network-independent representation of supported Telegram t.me links. */
sealed interface TelegramLink {
    val raw: String

    data class PublicChat(val username: String, override val raw: String) : TelegramLink
    data class PublicMessage(
        val username: String,
        val messageId: Long,
        val topicId: Long? = null,
        override val raw: String,
    ) : TelegramLink
    data class PrivateMessage(
        val chatId: Long,
        val messageId: Long,
        val topicId: Long? = null,
        override val raw: String,
    ) : TelegramLink
    data class Invite(val inviteLink: String, override val raw: String) : TelegramLink
}

object TelegramLinkParser {
    private val username = Regex("^[A-Za-z0-9_]{5,32}$")
    private val numeric = Regex("^\\d+$")

    fun parse(input: String): TelegramLink? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val url = normalizeUrl(raw) ?: return null
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "t.me" && host != "telegram.me" && host != "www.t.me" && host != "www.telegram.me") return null
        val parts = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val first = parts.first()
        if (first == "+" || first.startsWith("+")) {
            val hash = first.removePrefix("+")
            return if (hash.isNotBlank()) TelegramLink.Invite("https://t.me/+$hash", raw) else null
        }
        if (first == "joinchat" && parts.size >= 2 && parts[1].isNotBlank()) {
            return TelegramLink.Invite("https://t.me/joinchat/${parts[1]}", raw)
        }
        if (first == "c") return parsePrivate(parts, raw)
        if (!username.matches(first)) return null
        if (parts.size == 1) return TelegramLink.PublicChat(first, raw)
        if (parts.size == 2 && numeric.matches(parts[1])) {
            return TelegramLink.PublicMessage(first, parts[1].toLong(), raw = raw)
        }
        if (parts.size == 3 && numeric.matches(parts[1]) && numeric.matches(parts[2])) {
            return TelegramLink.PublicMessage(first, parts[2].toLong(), parts[1].toLong(), raw)
        }
        return null
    }

    fun normalizeUrl(input: String): String? {
        val value = input.trim()
        if (value.startsWith("@")) return "https://t.me/${value.drop(1)}"
        return when {
            value.startsWith("https://", true) -> value
            value.startsWith("http://", true) -> "https://${value.drop(7)}"
            value.startsWith("t.me/", true) -> "https://$value"
            value.startsWith("telegram.me/", true) -> "https://$value"
            else -> null
        }
    }

    private fun parsePrivate(parts: List<String>, raw: String): TelegramLink? {
        if (parts.size !in 3..4 || !numeric.matches(parts[1])) return null
        val internalId = parts[1].toLongOrNull() ?: return null
        if (internalId <= 0) return null
        val chatId = -100_000_000_0000L - internalId
        return when {
            parts.size == 3 && numeric.matches(parts[2]) ->
                TelegramLink.PrivateMessage(chatId, parts[2].toLong(), raw = raw)
            parts.size == 4 && numeric.matches(parts[2]) && numeric.matches(parts[3]) ->
                TelegramLink.PrivateMessage(chatId, parts[3].toLong(), parts[2].toLong(), raw)
            else -> null
        }
    }
}
