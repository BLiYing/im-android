package com.libeyond.imandroid.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationRowStyleTest {
    @Test fun mutedWithoutMentionIsGray() {
        assertEquals(false, ConversationRowStyle.strongAlert(mutedNow = true, mentionUnread = false))
    }

    @Test fun mentionPunchesThroughMute() {
        assertEquals(true, ConversationRowStyle.strongAlert(mutedNow = true, mentionUnread = true))
    }

    @Test fun notMutedIsBlueRegardlessOfMention() {
        assertEquals(true, ConversationRowStyle.strongAlert(mutedNow = false, mentionUnread = false))
        assertEquals(true, ConversationRowStyle.strongAlert(mutedNow = false, mentionUnread = true))
    }

    @Test fun compactCountMatchesIos() {
        assertEquals("0", ConversationRowStyle.compactCount(0))
        assertEquals("99", ConversationRowStyle.compactCount(99))
        assertEquals("100", ConversationRowStyle.compactCount(100))
        assertEquals("999", ConversationRowStyle.compactCount(999))
        assertEquals("1K", ConversationRowStyle.compactCount(1000))
        assertEquals("1K", ConversationRowStyle.compactCount(1099))
        assertEquals("1.2K", ConversationRowStyle.compactCount(1234))
        assertEquals("1M", ConversationRowStyle.compactCount(1_000_000))
        assertEquals("1.5M", ConversationRowStyle.compactCount(1_550_000))
    }
}
