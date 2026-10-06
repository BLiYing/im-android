package com.libeyond.imandroid.data

import com.libeyond.imandroid.data.AlbumTick.Member
import com.libeyond.imandroid.data.AlbumTick.State
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumTickTest {
    private val sent = listOf(Member(10, false), Member(11, false), Member(12, false))

    @Test fun notMineOrEmptyIsNone() {
        assertEquals(State.None, AlbumTick.state(sent, mine = false, readSeq = 99))
        assertEquals(State.None, AlbumTick.state(emptyList(), mine = true, readSeq = 99))
    }

    @Test fun anyFailedIsNoneEvenIfOthersUnsent() {
        assertEquals(State.None, AlbumTick.state(sent + Member(0, true), true, 99))
        assertEquals(State.None, AlbumTick.state(listOf(Member(0, false), Member(0, true)), true, 0))
    }

    @Test fun anyUnsentIsSending() {
        assertEquals(State.Sending, AlbumTick.state(sent + Member(0, false), true, 99))
    }

    @Test fun allSentJudgedByLastMember() {
        assertEquals(State.Sent, AlbumTick.state(sent, true, 0))
        assertEquals(State.Sent, AlbumTick.state(sent, true, 11))
        assertEquals(State.Read, AlbumTick.state(sent, true, 12))
    }

    @Test fun superGroupHidden() {
        assertEquals(State.None, AlbumTick.state(sent, true, ReadTick.HIDDEN))
    }
}
