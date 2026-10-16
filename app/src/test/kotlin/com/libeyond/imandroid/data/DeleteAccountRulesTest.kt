package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.AccountApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteAccountRulesTest {
    @Test fun emptyPasswordDisablesButton() {
        assertFalse(DeleteAccountRules.canSubmit("", false, emptyList()))
        assertTrue(DeleteAccountRules.canSubmit("x", false, emptyList()))
    }

    @Test fun submittingOrOwnedGroupsBlock() {
        assertFalse(DeleteAccountRules.canSubmit("x", true, emptyList()))
        assertFalse(DeleteAccountRules.canSubmit("x", false, listOf("群A")))
    }

    @Test fun listsAtMostThreeGroupsAndCountsRest() {
        val g = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("a", "b", "c"), DeleteAccountRules.listedGroups(g))
        assertEquals(2, DeleteAccountRules.moreCount(g))
        assertEquals(0, DeleteAccountRules.moreCount(g.take(3)))
    }

    @Test fun parsesOwnedGroupsFromStringsAndObjects() {
        val j = Json.parseToJsonElement("""{"owned_groups":["A",{"name":"B"},{"group_name":"C"},{"x":1},""]}""")
        assertEquals(listOf("A", "B", "C"), AccountApi.ownedGroups(j))
        assertEquals(emptyList<String>(), AccountApi.ownedGroups(null))
        assertEquals(emptyList<String>(), AccountApi.ownedGroups(Json.parseToJsonElement("{}")))
    }

    @Test fun deleteAtAcceptsSecondsAndMillis() {
        assertEquals(1_700_000_000_000L, AccountApi.deleteAtMillis(Json.parseToJsonElement("""{"delete_at":1700000000}""")))
        assertEquals(1_700_000_000_000L, AccountApi.deleteAtMillis(Json.parseToJsonElement("""{"delete_at":1700000000000}""")))
        assertNull(AccountApi.deleteAtMillis(Json.parseToJsonElement("""{"delete_at":"soon"}""")))
        assertNull(AccountApi.deleteAtMillis(null))
    }
}
