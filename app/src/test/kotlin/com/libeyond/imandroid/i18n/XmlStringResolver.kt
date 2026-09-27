package com.libeyond.imandroid.i18n

import com.libeyond.imandroid.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * JVM 单测里的 [StringResolver]（经 `META-INF/services` 被 [Str] 自动发现）：按 R.string 字段名反查
 * `src/main/res/values/` 下的 XML（默认语言 = 简体中文），所以既有测试里的中文断言原样成立。
 */
class XmlStringResolver(
    /** `null` = 只读默认 `values/`；`"en"` = 先读 `values/` 再用 `values-en/` 覆盖（同 Android 的资源回退）。 */
    private val qualifier: String? = null,
) : StringResolver {
    override val languageTag = qualifier ?: "zh-Hans"
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()
    private val stringNames: Map<Int, String> = namesOf(R.string::class.java)
    private val pluralNames: Map<Int, String> = namesOf(R.plurals::class.java)

    init {
        val res = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        listOfNotNull("values", qualifier?.let { "values-$it" }).forEach { name ->
            val dir = File(res, name)
            val files = dir.listFiles { f -> f.extension == "xml" }
                ?: error("XmlStringResolver: 资源目录不存在或不是目录 — ${dir.path}（qualifier=$qualifier）")
            files.forEach(::load)
        }
    }

    private fun namesOf(cls: Class<*>): Map<Int, String> =
        cls.fields.associate { it.getInt(null) to it.name }

    private fun load(file: File) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = doc.documentElement
        val s = root.getElementsByTagName("string")
        for (i in 0 until s.length) {
            val e = s.item(i) as Element
            strings[e.getAttribute("name")] = unescape(e.textContent)
        }
        val p = root.getElementsByTagName("plurals")
        for (i in 0 until p.length) {
            val e = p.item(i) as Element
            val items = e.getElementsByTagName("item")
            plurals[e.getAttribute("name")] = (0 until items.length).associate {
                val item = items.item(it) as Element
                item.getAttribute("quantity") to unescape(item.textContent)
            }
        }
    }

    private fun unescape(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                sb.append(if (raw[i + 1] == 'n') '\n' else if (raw[i + 1] == 't') '\t' else raw[i + 1])
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun fmt(template: String, args: Array<out Any>): String =
        if (args.isEmpty()) template else String.format(template, *args)

    override fun get(id: Int, args: Array<out Any>): String {
        val name = stringNames[id] ?: error("未知 string id $id")
        return fmt(strings[name] ?: error("values/ 下没有 string「$name」"), args)
    }

    override fun plural(id: Int, count: Int, args: Array<out Any>): String {
        val name = pluralNames[id] ?: error("未知 plurals id $id")
        val forms = plurals[name] ?: error("values/ 下没有 plurals「$name」")
        val template = (if (count == 1) forms["one"] else null) ?: forms["other"] ?: forms.values.first()
        return fmt(template, args)
    }
}
