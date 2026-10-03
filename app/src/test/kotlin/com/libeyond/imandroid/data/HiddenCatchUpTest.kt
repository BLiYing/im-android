package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.HiddenItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenCatchUpTest {
    @Test fun `按会话归并并去重，脏项丢掉`() {
        val m = HiddenCatchUp.groupByConv(
            listOf(
                HiddenItem("a", 3), HiddenItem("a", 5), HiddenItem("a", 3),
                HiddenItem("b", 1),
                HiddenItem("", 9), HiddenItem("c", 0), HiddenItem("c", -2),
            ),
        )
        assertEquals(mapOf("a" to listOf(3L, 5L), "b" to listOf(1L)), m)
    }

    @Test fun `空集合给空表`() = assertEquals(emptyMap<String, List<Long>>(), HiddenCatchUp.groupByConv(emptyList()))
}
