package com.libeyond.imandroid.sdk.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PerfMarksTest {
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        PerfMarks.resetForTest()
        IMLog.useSinksForTest(IMLog.Sink { _, _, event, fields, _ -> events += "$event:${fields["conv_id"] ?: ""}" })
    }

    @After
    fun tearDown() = IMLog.useSinksForTest()

    @Test
    fun jumpDoneWithoutBeginIsNotLogged() {
        PerfMarks.jumpBottomDone("c1")
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun jumpDonePairsWithBeginOnce() {
        PerfMarks.jumpBottomBegin("c1")
        PerfMarks.jumpBottomDone("c1")
        PerfMarks.jumpBottomDone("c1")
        assertEquals(listOf("jump_bottom_begin:c1", "jump_bottom_done:c1"), events)
    }

    @Test
    fun conversationListVisibleOnlyFirstTime() {
        PerfMarks.conversationListVisible()
        PerfMarks.conversationListVisible()
        assertEquals(listOf("conv_list_visible:"), events)
    }
}
