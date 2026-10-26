package com.libeyond.imandroid.data

import com.libeyond.imandroid.R
import com.libeyond.imandroid.i18n.Str

/** 「谁能看到」的三档（PRIVACY_VISIBILITY_DESIGN §1）。[wire] 是 `GET/PUT /users/me/privacy` 的取值。 */
enum class Visibility(val wire: String, private val labelRes: Int) {
    Everyone("everyone", R.string.ps_visibility_everyone),
    Contacts("contacts", R.string.ps_visibility_contacts),
    Nobody("nobody", R.string.ps_visibility_nobody);

    val label: String get() = Str.s(labelRes)

    companion object {
        /** 页面上的展示顺序（草图：所有人 / 我的联系人 / 无人）。 */
        val ORDER: List<Visibility> = listOf(Everyone, Contacts, Nobody)

        /** 未知 / 空取值返回 null（调用方保持「还没拉到」的空右值，不猜档位）。 */
        fun parse(wire: String?): Visibility? = entries.firstOrNull { it.wire == wire }
    }
}

/** 哪一项可见性（页标题、页脚、PUT 字段都按它选）。 */
enum class VisibilityKind { LastSeen, Avatar }

/**
 * 一项可见性的「已提交值 + 页面上显示的值」。选中即**乐观**显示新值，保存失败回滚到已提交值（设计 §4）。
 * 纯逻辑、不碰 Compose：Host 持有它并在变化后触发重组。
 */
class VisibilityField(initial: Visibility? = null) {
    var committed: Visibility? = initial
        private set
    var shown: Visibility? = initial
        private set
    private var saving = false

    /** 正在保存：Host 据此在发起前直接忽略重复点击（不弹「保存失败」）。 */
    val isSaving: Boolean get() = saving

    /** 服务端 GET 回来的权威值。保存途中不覆盖 [shown]（以免打断乐观显示）。 */
    fun load(v: Visibility?) {
        if (v == null) return
        committed = v
        if (!saving) shown = v
    }

    /** 重进页面时让选择页的勾回到已提交值。 */
    fun resetShown() { if (!saving) shown = committed }

    /**
     * 选中 [v]：先乐观显示，再调 [save]；成功提交，失败（含取消以外的任何异常）回滚并返回 false。
     * 保存途中的重复点击直接忽略（返回 false）。取消异常照常抛出，不当作失败。
     */
    suspend fun choose(v: Visibility, save: suspend (Visibility) -> Unit): Boolean {
        if (saving) return false
        saving = true
        shown = v
        try {
            save(v)
            committed = v
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            shown = committed
            throw e
        } catch (e: Exception) {
            shown = committed
            return false
        } finally {
            saving = false
        }
    }
}
