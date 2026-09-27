package com.libeyond.imandroid.i18n

import com.libeyond.imandroid.R
import org.junit.Assert.assertEquals
import org.junit.Test

class StrTest {
    @Test fun plainString() = assertEquals("语言", Str.s(R.string.settings_language_title))

    @Test fun formatArgs() = assertEquals("小明的聊天详情", Str.s(R.string.chat_detail_a11y_with_name, "小明"))

    @Test fun escapedPercent() = assertEquals("下载中 42%", Str.s(R.string.chat_media_downloading_progress, 42))

    @Test fun plural() = assertEquals("3 人申请加入本群 · 点击审批", Str.p(R.plurals.chat_banner_join_pending, 3, 3))
}
