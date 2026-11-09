package com.libeyond.imandroid.sdk.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `public-config` 的 `register_enabled`：只有明确 false 才算关闭，其余一律开放（S4）。 */
class PublicConfigApiTest {
    @Test fun `明确 false 才关闭`() =
        assertFalse(PublicConfigApi.registerEnabledOf(buildJsonObject { put("register_enabled", JsonPrimitive(false)) }))

    @Test fun `true 缺失 非布尔 data 为空都当开放`() {
        assertTrue(PublicConfigApi.registerEnabledOf(buildJsonObject { put("register_enabled", JsonPrimitive(true)) }))
        assertTrue(PublicConfigApi.registerEnabledOf(buildJsonObject { }))
        assertTrue(PublicConfigApi.registerEnabledOf(buildJsonObject { put("register_enabled", JsonPrimitive("nope")) }))
        assertTrue(PublicConfigApi.registerEnabledOf(JsonNull))
        assertTrue(PublicConfigApi.registerEnabledOf(null))
    }
}
