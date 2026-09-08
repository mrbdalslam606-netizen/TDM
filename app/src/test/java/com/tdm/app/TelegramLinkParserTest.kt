package com.tdm.app

import com.tdm.app.telegram.TelegramLink
import com.tdm.app.telegram.TelegramLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramLinkParserTest {
    @Test
    fun parsesPublicChannelLink() {
        val result = TelegramLinkParser.parse("https://t.me/Batonz_om")
        assertEquals(TelegramLink.PublicChat("Batonz_om", "https://t.me/Batonz_om"), result)
    }

    @Test
    fun parsesPublicMessageLink() {
        val result = TelegramLinkParser.parse("https://t.me/Batonz_om/2")
        assertEquals(
            TelegramLink.PublicMessage("Batonz_om", 2L, raw = "https://t.me/Batonz_om/2"),
            result,
        )
    }

    @Test
    fun parsesPrivateTopicMessageLink() {
        val result = TelegramLinkParser.parse("https://t.me/c/4491970777/2/53")
        assertEquals(
            TelegramLink.PrivateMessage(
                chatId = -1004491970777L,
                messageId = 53L,
                topicId = 2L,
                raw = "https://t.me/c/4491970777/2/53",
            ),
            result,
        )
    }

    @Test
    fun parsesPrivateMessageWithoutTopic() {
        val result = TelegramLinkParser.parse("https://t.me/c/4491970777/53")
        assertTrue(result is TelegramLink.PrivateMessage)
        assertEquals(-1004491970777L, (result as TelegramLink.PrivateMessage).chatId)
        assertEquals(53L, result.messageId)
        assertNull(result.topicId)
    }

    @Test
    fun parsesPrivateInviteLinkWithoutJoiningAutomatically() {
        val result = TelegramLinkParser.parse("https://t.me/+j-qwuSN18ctiYTQ0")
        assertEquals(
            TelegramLink.Invite(
                "https://t.me/+j-qwuSN18ctiYTQ0",
                "https://t.me/+j-qwuSN18ctiYTQ0",
            ),
            result,
        )
    }

    @Test
    fun acceptsUsernameShorthandAndRejectsUnsupportedLinks() {
        assertEquals(
            TelegramLink.PublicChat("Batonz_om", "@Batonz_om"),
            TelegramLinkParser.parse("@Batonz_om"),
        )
        assertNull(TelegramLinkParser.parse("https://example.com/Batonz_om"))
        assertNull(TelegramLinkParser.parse("https://t.me/c/not-a-number/2"))
        assertNull(TelegramLinkParser.parse("https://t.me/+"))
    }
}
