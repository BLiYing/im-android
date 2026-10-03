package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.InviteResult
import com.libeyond.imandroid.sdk.api.JoinRequest
import com.libeyond.imandroid.sdk.protocol.ProtocolJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class InviteOutcomeTest {
    private fun parse(s: String): InviteResult = InviteResult.parse(Json.parseToJsonElement(s))

    @Test fun parsesAddedAndPending() {
        val r = parse("""{"added":["a"],"pending":["b","c"]}""")
        assertEquals(listOf("a"), r.added)
        assertEquals(listOf("b", "c"), r.pending)
        assertEquals(true, r.known)
    }

    @Test fun oldServerWithoutFieldsIsUnknown() {
        assertFalse(parse("""{}""").known)
        assertFalse(InviteResult.parse(null as JsonElement?).known)
        assertEquals(InviteOutcome.Plain, InviteOutcome.of(2, parse("""{}""")))
    }

    @Test fun pendingWins() {
        assertEquals(InviteOutcome.Pending, InviteOutcome.of(2, parse("""{"added":["a"],"pending":["b"]}""")))
        assertEquals(InviteOutcome.Pending, InviteOutcome.of(1, parse("""{"added":[],"pending":["b"]}""")))
    }

    @Test fun bothEmptyMeansAllInGroup() {
        assertEquals(InviteOutcome.AllIn, InviteOutcome.of(2, parse("""{"added":[],"pending":[]}""")))
    }

    @Test fun partialAndPlain() {
        assertEquals(InviteOutcome.Partial(1, 1), InviteOutcome.of(2, parse("""{"added":["a"],"pending":[]}""")))
        assertEquals(InviteOutcome.Plain, InviteOutcome.of(2, parse("""{"added":["a","b"],"pending":[]}""")))
    }

    @Test fun joinRequestInviterNickname() {
        val a = ProtocolJson.decodeFromString(JoinRequest.serializer(), """{"user_id":"u","inviter_nickname":"张三","hello":"hi"}""")
        assertEquals("张三", a.invitedByName)
        val b = ProtocolJson.decodeFromString(JoinRequest.serializer(), """{"user_id":"u","hello":"hi"}""")
        assertNull(b.invitedByName)
        val c = ProtocolJson.decodeFromString(JoinRequest.serializer(), """{"user_id":"u","inviter_nickname":"  "}""")
        assertNull(c.invitedByName)
    }
}
