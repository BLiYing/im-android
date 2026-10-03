package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.protocol.MentionSpan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResendMentionAllTest {
    private val allSpans = Mention.encodeSpans(listOf(MentionSpan(offset = 0, length = 4, uid = "")))

    @Test fun `自己发的带 @所有人 的行重发仍是 mention_all`() = assertTrue(Mention.mentionAllForResend(null, allSpans))
    @Test fun `转发行重发不重放 @所有人`() = assertFalse(Mention.mentionAllForResend("someone", allSpans))
    @Test fun `没有片段就不是`() = assertFalse(Mention.mentionAllForResend(null, null))
}
