package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.AccountApi
import com.libeyond.imandroid.sdk.api.OwnedGroup
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteAccountRulesTest {
    @Test fun emptyPasswordDisablesButton() {
        assertFalse(DeleteAccountRules.canSubmit("", false, emptyList<OwnedGroup>()))
        assertTrue(DeleteAccountRules.canSubmit("x", false, emptyList<OwnedGroup>()))
    }

    @Test fun submittingOrOwnedGroupsBlock() {
        assertFalse(DeleteAccountRules.canSubmit("x", true, emptyList<OwnedGroup>()))
        assertFalse(DeleteAccountRules.canSubmit("x", false, listOf(OwnedGroup("c1", "群A"))))
    }

    @Test fun listsAtMostThreeGroupsAndCountsRest() {
        val g = listOf("a", "b", "c", "d", "e").map { OwnedGroup(it, it) }
        assertEquals(g.take(3), DeleteAccountRules.listedGroups(g))
        assertEquals(2, DeleteAccountRules.moreCount(g))
        assertEquals(0, DeleteAccountRules.moreCount(g.take(3)))
    }

    @Test fun parsesOwnedGroupsFromContract() {
        val j = Json.parseToJsonElement("""{"owned_groups":[{"conv_id":"g_1","name":"A"},{"name":"B"},{"conv_id":"g_3"},"x",{"conv_id":"g_4","name":" "}]}""")
        assertEquals(listOf(OwnedGroup("g_1", "A"), OwnedGroup("", "B"), OwnedGroup("g_3", ""), OwnedGroup("g_4", " ")), AccountApi.ownedGroups(j))
        assertEquals(emptyList<OwnedGroup>(), AccountApi.ownedGroups(Json.parseToJsonElement("""{"owned_groups":[{"name":" "},{}]}""")))
        assertEquals(emptyList<OwnedGroup>(), AccountApi.ownedGroups(null))
        assertEquals(emptyList<OwnedGroup>(), AccountApi.ownedGroups(Json.parseToJsonElement("{}")))
    }

    @Test fun deleteAtAcceptsSecondsAndMillis() {
        assertEquals(1_700_000_000_000L, AccountApi.deleteAtMillis(Json.parseToJsonElement("""{"delete_at":1700000000}""")))
        assertEquals(1_700_000_000_000L, AccountApi.deleteAtMillis(Json.parseToJsonElement("""{"delete_at":1700000000000}""")))
        assertNull(AccountApi.deleteAtMillis(Json.parseToJsonElement("""{"delete_at":"soon"}""")))
        assertNull(AccountApi.deleteAtMillis(null))
    }
}
