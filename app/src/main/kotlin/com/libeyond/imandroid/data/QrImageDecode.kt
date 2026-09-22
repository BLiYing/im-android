package com.libeyond.imandroid.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/**
 * 从一张图片的像素里识别二维码（相册选图入口，QRCODE P0 接收方半，对齐 iOS `IMQRImage.decodeAllInImage:`
 * 的 `CIDetector` 一图多码能力）。
 *
 * 入参是解码后的 ARGB 像素数组而不是 `android.graphics.Bitmap`——纯 JVM，能进普通单测
 * （出示码那半 `QrEncode` 同一个理由，本仓没有 Robolectric）；Android 侧从 `Bitmap.getPixels`
 * 摘出来喂进来（见 `ui/QrScanHost.kt`）。
 *
 * 用 zxing-core 的 `QRCodeMultiReader`（一次识别多枚，出示码那半已引入的同一个依赖，未叠 ML Kit）。
 */
object QrImageDecode {
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    /**
     * @return 去重后的原文列表，**按码在图上的包围盒面积从大到小排**（对齐 iOS
     *   `IMQRImage.decodeAllInImage:` 按 `CIFeature.bounds` 面积降序——面积大的通常是用户
     *   想扫的那张，群公告截图里主码大、水印/客服码小，候选列表第一项该是主码）。
     *   识别不到回空列表——**不抛异常**，调用方按空/一枚/多枚三态分支
     *   （空=提示"没有识别到二维码"，一枚=直接当结果处理，多枚=弹候选列表让用户选）。
     */
    fun decode(width: Int, height: Int, pixels: IntArray): List<String> {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return emptyList()
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        return try {
            QRCodeMultiReader().decodeMultiple(binary, hints)
                .sortedByDescending { boundingArea(it) }
                .map { it.text }
                .distinct()
        } catch (e: NotFoundException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 识别结果的定位点包围盒面积；无定位点（理论上不会发生）时排最后。
     *
     * **验证纪律的诚实记录**：`QRCodeMultiReader` 内部的 `MultiFinderPatternFinder` 本就按模块
     * 尺寸聚类，实测输出恰好已经是大码在前——临时去掉这行 `sortedByDescending` 单测仍然绿，
     * 没能造出「红」。显式排序留着是**不依赖 zxing 未文档化的内部实现顺序**（换个版本可能就变），
     * 不是修一个已观察到的乱序 bug；单测锁的是**输出契约**本身，不是这行代码有没有生效。
     */
    private fun boundingArea(result: Result): Double {
        val points = result.resultPoints?.filterNotNull().orEmpty()
        if (points.isEmpty()) return 0.0
        val w = (points.maxOf { it.x } - points.minOf { it.x }).toDouble()
        val h = (points.maxOf { it.y } - points.minOf { it.y }).toDouble()
        return w * h
    }
}
