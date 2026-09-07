package com.libeyond.mediapicker

/**
 * 分页续接的判定（纯逻辑，可单测）。查询本身在 [MediaStoreSource]，那层进不了 JVM 单测。
 *
 * 两条都出过事的规矩：
 * 1. **按 id 去重**——分页期间用户拍了张新照片，整个列表就往后挪一位，
 *    同一条会同时出现在第 1 页尾和第 2 页头。不去重的话 `LazyVerticalGrid` 的 key 重复，**当场抛**。
 * 2. **不满一页就是到底了**——不置这个标志，滑到底会无限重查最后一页
 *    （每次都返回 0 条，但每次都再发一次查询）。
 */
object PageMerge {

    data class Result(val assets: List<MediaAsset>, val exhausted: Boolean)

    fun merge(current: List<MediaAsset>, more: List<MediaAsset>, pageSize: Int): Result {
        val known = current.mapTo(HashSet()) { it.id }
        val added = more.filter { known.add(it.id) }
        // **判「到底」看的是这一页原始条数，不是去重后的条数**：
        // 满满一页全是重复（列表整体挪位时会发生）说明后面还有，不该就此停住。
        return Result(current + added, more.size < pageSize)
    }
}
