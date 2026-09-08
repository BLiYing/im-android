package com.libeyond.imandroid.data

/**
 * 文件类型图标的**类型判定与配色**（对齐 iOS `IMFileTypeIdentifierForName` / `FileType_*.imageset`）。
 *
 * iOS 那 22 张是 **SVG**，而 Android 的 vector drawable **不支持 `<text>`**——
 * 那些图的角标（PDF / W / X / `{ }` / `</>`）都是文字元素，直接转会全丢。
 * 所以本端按用户的意思「自己改改」：**同一套设计在 Compose 里重画**
 * （同样的页面外形、同样的渐变色、同样的角标），见 `ui/components/FileTypeIcon.kt`。
 * 差异登记在 `docs/UI_PARITY_IOS.md`。
 *
 * 扩展名清单**逐条照抄 iOS**，别自己发挥——两端认的类型不一样，同一个文件在两端会是两种图标。
 */
object FileTypeIcons {

    /** 未知类型。iOS 同名。 */
    const val UNKNOWN = "unknown"

    private val TABLE: List<Pair<String, List<String>>> = listOf(
        "pdf" to listOf("pdf"),
        "word" to listOf("doc", "docx", "docm", "dot", "dotx", "odt"),
        "excel" to listOf("xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx", "ods"),
        "powerpoint" to listOf("ppt", "pptx", "pptm", "pps", "ppsx", "odp"),
        "csv" to listOf("csv", "tsv"),
        "pages" to listOf("pages"),
        "numbers" to listOf("numbers"),
        "keynote" to listOf("key"),
        "text" to listOf("txt", "rtf", "rtfd", "log"),
        "markdown" to listOf("md", "markdown"),
        "xml" to listOf("xml", "xsd", "xsl", "xslt", "plist"),
        "json" to listOf("json", "geojson"),
        "image" to listOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp",
            "tif", "tiff", "svg", "ico", "raw", "dng", "psd",
        ),
        "video" to listOf("mp4", "mov", "m4v", "avi", "mkv", "webm", "wmv", "flv", "mpg", "mpeg", "3gp"),
        "audio" to listOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "aiff", "caf"),
        "archive" to listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz"),
        "code" to listOf(
            "html", "htm", "css", "scss", "less", "js", "jsx", "ts", "tsx",
            "swift", "m", "mm", "h", "c", "cc", "cpp", "cxx", "java", "kt",
            "kts", "py", "go", "rs", "rb", "php", "sh", "zsh", "yaml", "yml", "toml", "ini",
        ),
        "database" to listOf("db", "sqlite", "sqlite3", "sql", "mdb", "accdb"),
        "font" to listOf("ttf", "otf", "woff", "woff2", "eot"),
        "ebook" to listOf("epub", "mobi", "azw", "azw3", "fb2"),
        "package" to listOf("dmg", "pkg", "exe", "msi", "apk", "ipa", "appimage", "deb", "rpm"),
    )

    private val BY_EXT: Map<String, String> =
        TABLE.flatMap { (kind, exts) -> exts.map { it to kind } }.toMap()

    /**
     * 文件名 → 类型。
     *
     * **只认最后一个点之后那截**，且大小写不敏感。取不到 / 认不出一律 [UNKNOWN]——
     * 猜错类型比给个问号更糟（用户会以为是别的文件）。
     */
    fun kindFor(fileName: String?): String {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext.isEmpty() || ext.length > 12) return UNKNOWN
        return BY_EXT[ext] ?: UNKNOWN
    }

    /** 渐变两端色（逐个照抄 iOS 的 `linearGradient` stop）。 */
    fun gradient(kind: String): Pair<Long, Long> = when (kind) {
        "pdf" -> 0xFFFF6558 to 0xFFDC302C
        "word" -> 0xFF4A82FF to 0xFF164FD0
        "excel" -> 0xFF35C77E to 0xFF07844D
        "powerpoint" -> 0xFFFF8254 to 0xFFD74928
        "csv" -> 0xFF31CBC4 to 0xFF0A9197
        "pages" -> 0xFFFFB52B to 0xFFED8200
        "numbers" -> 0xFF68DC55 to 0xFF22A630
        "keynote" -> 0xFFA060F3 to 0xFF6332BF
        "text" -> 0xFF91A8BF to 0xFF5C7187
        "markdown" -> 0xFF637898 to 0xFF30445F
        "xml" -> 0xFF2BC9E2 to 0xFF087FB4
        "json" -> 0xFFEFBD3E to 0xFFCE8700
        "image" -> 0xFFFF6A62 to 0xFFDF3E86
        "video" -> 0xFF617FFF to 0xFF3148C9
        "audio" -> 0xFFA869EE to 0xFF6331BA
        "archive" -> 0xFFFFB42E to 0xFFDF7A00
        "code" -> 0xFF3B8CFF to 0xFF0B4FC7
        "database" -> 0xFFC45FCA to 0xFF792681
        "font" -> 0xFF626A76 to 0xFF30353D
        "ebook" -> 0xFF30BDB0 to 0xFF087D75
        "package" -> 0xFFA67A56 to 0xFF66472F
        else -> 0xFFA4ACB7 to 0xFF666E79
    }

    /**
     * 角标文字。**空串 = 这一类在 iOS 上画的是图形不是文字**
     * （archive 的三个方块、audio 的波形、image 的山、video 的三角、text 的四条横线…），
     * 由渲染层用等价的白色图形补上。
     */
    fun label(kind: String): String = when (kind) {
        "pdf" -> "PDF"
        "word" -> "W"
        "excel" -> "X"
        "powerpoint" -> "PPT"
        "markdown" -> "MD"
        "json" -> "{ }"
        "code" -> "</>"
        "font" -> "A"
        UNKNOWN -> "?"
        else -> ""
    }
}
