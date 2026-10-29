package com.libeyond.imandroid.data

import com.libeyond.imandroid.sdk.api.PrivacyApi
import com.libeyond.imandroid.sdk.api.PrivacySettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyVisibilityTest {

    @Test
    fun parse_known_levels_and_reject_unknown() {
        assertEquals(Visibility.Everyone, Visibility.parse("everyone"))
        assertEquals(Visibility.Contacts, Visibility.parse("contacts"))
        assertEquals(Visibility.Nobody, Visibility.parse("nobody"))
        assertNull(Visibility.parse(""))
        assertNull(Visibility.parse(null))
        assertNull(Visibility.parse("EVERYONE"))
        assertEquals(listOf("所有人", "我的联系人", "无人"), Visibility.ORDER.map { it.label })
    }

    @Test
    fun get_response_decodes_with_defaults_for_old_servers() {
        val j = Json { ignoreUnknownKeys = true }
        val full = j.decodeFromString(PrivacySettings.serializer(),
            """{"auto_delete_default_sec":86400,"last_seen_visibility":"nobody","avatar_visibility":"contacts"}""")
        assertEquals(86400, full.autoDeleteDefaultSec)
        assertEquals(Visibility.Nobody, Visibility.parse(full.lastSeenVisibility))
        assertEquals(Visibility.Contacts, Visibility.parse(full.avatarVisibility))
        val old = j.decodeFromString(PrivacySettings.serializer(), """{"auto_delete_default_sec":0}""")
        assertEquals(Visibility.Contacts, Visibility.parse(old.lastSeenVisibility))
        assertEquals(Visibility.Everyone, Visibility.parse(old.avatarVisibility))
    }

    @Test
    fun put_body_carries_only_the_changed_field() {
        val ls = PrivacyApi.body(lastSeen = "nobody")
        assertEquals(setOf("last_seen_visibility"), ls.keys)
        assertEquals("nobody", ls.getValue("last_seen_visibility").jsonPrimitive.content)
        val av = PrivacyApi.body(avatar = "contacts")
        assertEquals(setOf("avatar_visibility"), av.keys)
        val ad = PrivacyApi.body(autoDeleteDefaultSec = 604800)
        assertEquals(setOf("auto_delete_default_sec"), ad.keys)
        assertEquals(604800, ad.getValue("auto_delete_default_sec").jsonPrimitive.int)
    }

    @Test
    fun bio_and_phone_levels_default_and_put_only_changed_field() {
        // 老服务端没有这两个字段：个人简介按 everyone、手机号码按 nobody（= 既有行为）
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(PrivacySettings.serializer(), """{"auto_delete_default_sec":0}""")
        assertEquals(Visibility.Everyone, Visibility.parse(old.bioVisibility))
        assertEquals(Visibility.Nobody, Visibility.parse(old.phoneVisibility))
        val bio = PrivacyApi.body(bio = "nobody")
        assertEquals(setOf("bio_visibility"), bio.keys)
        val phone = PrivacyApi.body(phone = "contacts")
        assertEquals(setOf("phone_visibility"), phone.keys)
        assertEquals("contacts", phone.getValue("phone_visibility").jsonPrimitive.content)
    }

    @Test
    fun choose_success_commits() = runBlocking {
        val f = VisibilityField(Visibility.Contacts)
        var sent: Visibility? = null
        assertTrue(f.choose(Visibility.Nobody) { sent = it })
        assertEquals(Visibility.Nobody, sent)
        assertEquals(Visibility.Nobody, f.committed)
        assertEquals(Visibility.Nobody, f.shown)
    }

    @Test
    fun choose_failure_rolls_back_shown_value() = runBlocking {
        val f = VisibilityField(Visibility.Contacts)
        var shownDuringSave: Visibility? = null
        val ok = f.choose(Visibility.Nobody) { shownDuringSave = f.shown; error("net") }
        assertFalse(ok)
        assertEquals(Visibility.Nobody, shownDuringSave) // 乐观显示
        assertEquals(Visibility.Contacts, f.shown)       // 回滚
        assertEquals(Visibility.Contacts, f.committed)
    }

    @Test
    fun load_does_not_disturb_optimistic_value_while_saving() = runBlocking {
        val f = VisibilityField(null)
        f.load(Visibility.Everyone)
        assertEquals(Visibility.Everyone, f.shown)
        f.choose(Visibility.Nobody) { f.load(Visibility.Everyone) }
        assertEquals(Visibility.Nobody, f.committed) // 保存成功后以所选为准
    }

    @Test
    fun repeated_choose_while_saving_is_ignored() = runBlocking {
        val f = VisibilityField(Visibility.Contacts)
        var calls = 0
        var second: Boolean? = null
        var savingDuring = false
        f.choose(Visibility.Nobody) {
            calls++
            savingDuring = f.isSaving
            second = f.choose(Visibility.Everyone) { calls++ }
        }
        assertTrue(savingDuring)
        assertEquals(false, second)
        assertEquals(1, calls)
        assertEquals(Visibility.Nobody, f.committed)
        assertFalse(f.isSaving)
    }

    @Test
    fun cancellation_rolls_back_and_rethrows() = runBlocking {
        val f = VisibilityField(Visibility.Contacts)
        var thrown = false
        try {
            f.choose(Visibility.Nobody) { throw kotlinx.coroutines.CancellationException("x") }
        } catch (e: kotlinx.coroutines.CancellationException) { thrown = true }
        assertTrue(thrown)
        assertEquals(Visibility.Contacts, f.shown)
        assertFalse(f.isSaving)
    }
}
