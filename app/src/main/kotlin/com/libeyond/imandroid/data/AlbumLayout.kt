package com.libeyond.imandroid.data

/**
 * 相册宫格布局（M4+，PROTOCOL §4.3 `group_id`）。
 *
 * 同一批发出的多图/多视频**每张仍是独立消息**（可单独撤回/引用/转发/收藏），
 * 只是共享一个客户端生成的 `group_id`；客户端把同组成员**聚簇渲染成 Telegram 式宫格**。
 *
 * 行模式与尺寸逐条照抄 iOS `IMAlbumCell` 的 `IMAlbumRowPattern` / `IMAlbumHeightForCount`——
 * **这不是可以自己发挥的地方**：三端宫格排法不同的话，同一组图在三端裁剪出的构图都不一样，
 * 用户会以为发出去的东西被改了。
 */
object AlbumLayout {

    /** 宫格总宽（iOS `kIMAlbumWidth`）。 */
    const val WIDTH = 240f

    /** 格与格的间隙（iOS `kIMAlbumGap`）。 */
    const val GAP = 2f

    /** 单列那一行的固定高（iOS：`cols == 1 ? 150 : …`）。 */
    const val SINGLE_ROW_HEIGHT = 150f

    /** 一组最多几张——与系统选图器的 selectionLimit 同为 9。 */
    const val MAX = 9

    /**
     * 每行放几个。逐条照抄 iOS `IMAlbumRowPattern`：
     * 1→[1] 2→[2] 3→[1,2] 4→[2,2] 5→[2,3] 6→[3,3] 7→[1,3,3] 8→[2,3,3] 9+→[3,3,3]
     *
     * 注意 3 是 `[1,2]` 不是 `[2,1]`——大图在上、两张小的在下，这是 Telegram 的构图。
     */
    fun rowPattern(n: Int): List<Int> = when {
        n <= 0 -> emptyList()
        n == 1 -> listOf(1)
        n == 2 -> listOf(2)
        n == 3 -> listOf(1, 2)
        n == 4 -> listOf(2, 2)
        n == 5 -> listOf(2, 3)
        n == 6 -> listOf(3, 3)
        n == 7 -> listOf(1, 3, 3)
        n == 8 -> listOf(2, 3, 3)
        else -> listOf(3, 3, 3)   // 9 封顶
    }

    /** 某一行在给定列数下每格的高（= 宽，正方形；单列那行固定 150）。 */
    fun tileSize(cols: Int): Float =
        if (cols == 1) SINGLE_ROW_HEIGHT else (WIDTH - (cols - 1) * GAP) / cols

    /** 某一行每格的宽：单列那行占满整行（iOS：`cols == 1 ? kIMAlbumWidth : tileW`），其余等于 [tileSize]。 */
    fun tileWidth(cols: Int): Float = if (cols == 1) WIDTH else tileSize(cols)

    /**
     * 上传进度环在某个格子里该多大。
     *
     * **不是固定 44**：3 列宫格里格子只有 (240-4)/3 ≈ 78.7dp，44dp 的环占掉大半格、
     * 还压在时长角标上；而 2 列的格子有 119dp，28dp 的环又小得看不清。
     * 阈值取 100——2 列(119) 以上给大环，3 列(79) 给小环。
     */
    fun ringSize(tile: Float): Float = if (tile < 100f) 28f else 44f

    /** 环里还放不放得下百分比数字（小环放不下，只画环）。 */
    fun showsRingPercent(tile: Float): Boolean = tile >= 100f

    /**
     * 视频格中心要不要画播放角标（iOS `IMAlbumTileView` 的 `_playBadge`）。
     *
     * 中心位同一时刻只放一样东西：上传中（环）/ 发失败（❗）/ 没下载完（↓ ⏸ ↻ ⊘ 门控字形）都占着它，
     * 只有**就绪**的视频格才轮到播放角标。此前本端宫格里的视频格压根没画——下载完的视频
     * 在宫格里看不出是视频，而单条视频气泡与 iOS 宫格都有（2026-09-16 用户报）。
     */
    fun showsPlayBadge(isVideo: Boolean, sending: Boolean, failed: Boolean, gateReady: Boolean): Boolean =
        isVideo && !sending && !failed && gateReady

    /** 整个宫格的总高。行高确定 → cell 高确定，加载图片时不会跳版。 */
    fun heightFor(n: Int): Float {
        val rows = rowPattern(n)
        if (rows.isEmpty()) return 0f
        return rows.sumOf { (tileSize(it) + GAP).toDouble() }.toFloat() - GAP
    }

    /**
     * 这条消息能不能进宫格。
     *
     * 只有**图片/视频**才聚簇：同一 `group_id` 里混进文件或语音时，那几条各自单独显示
     * （服务端只透传 group_id 不校验类型，端上不能假定同组必然同型）。
     */
    fun isAlbumMember(contentType: String, groupId: String?): Boolean =
        !groupId.isNullOrBlank() &&
            (contentType == com.libeyond.imandroid.sdk.protocol.ContentType.IMAGE ||
                contentType == com.libeyond.imandroid.sdk.protocol.ContentType.VIDEO)
}
