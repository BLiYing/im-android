package com.libeyond.imandroid.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.composables.icons.lucide.Flashlight
import com.composables.icons.lucide.FlashlightOff
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.libeyond.imandroid.data.QrImageDecode
import com.libeyond.imandroid.data.qrScanLabelFor
import com.libeyond.imandroid.sdk.logging.IMLog
import com.libeyond.imandroid.ui.components.ActionSheet
import com.libeyond.imandroid.ui.components.IMToast
import com.libeyond.imandroid.ui.components.SheetItem
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * 扫一扫取景页（QRCODE P0 接收方半，对齐 iOS `IMQRScannerViewController` 的取景页）。
 *
 * CameraX 出帧 + zxing-core（`QRCodeReader`/`QRCodeMultiReader`，出示码那半已在用的同一个依赖）
 * 解码——不叠 ML Kit / zxing-android-embedded。**未接**：与「我的二维码」的页签组合（本端「我的
 * 二维码」另有独立入口，不需要在这里重复），见 `docs/UI_PARITY_IOS.md`。
 *
 * 命中一枚码就回调 [onResult] 一次并停止分析；页面本身何时关闭由调用方决定
 * （[QrRouteHost] 会先关本页再异步 resolve，对齐 iOS `handleRaw:` 先停帧、后解析的顺序）。
 * 「从相册选择」同一条出口：识别到一枚直接当结果，识别到多枚弹候选列表选一枚（对齐 iOS
 * `pickFromAlbum`/`handleDecodedCodes:`——群公告截图常同时有群码与客服码，不默认取第一个）。
 */
@Composable
internal fun QrScanHost(onResult: (String) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var deniedOnce by remember { mutableStateOf(false) }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) deniedOnce = true
    }
    LaunchedEffect(Unit) { if (!hasPermission) requestPermission.launch(Manifest.permission.CAMERA) }

    // 从「去设置开启」跳系统设置页回来时重新核对一次权限——系统弹窗只在首次 NotDetermined 时出现，
    // 用户在设置里手动开了权限不会主动通知这里，不补这一步会一直卡在「需要相机权限」直到关页重开（`/code-review` 提醒）。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !hasPermission) {
                hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var torchOn by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    // 命中一枚就锁死，忽略同一批还没关掉的后续帧——避免同一次扫描回调两次。
    // **用 AtomicBoolean 而不是 Compose 的 mutableStateOf**：相机命中来自 CameraX 后台分析线程
    // （`QrFrameAnalyzer` 跑在独立 executor 上），相册命中来自主线程协程，两条路径「先读再写」的
    // check-then-act 必须是原子的，否则用户扫的那张码同时也在相册里选中时，两条路可能都判定
    // 自己是第一个、各调一次 [onResult]（`/code-review` 抓出）——`compareAndSet` 保证谁先到谁赢。
    val handled = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    LaunchedEffect(camera, torchOn) {
        camera?.takeIf { it.cameraInfo.hasFlashUnit() }?.cameraControl?.enableTorch(torchOn)
    }

    var pickerBusy by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<String>?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null || handled.get()) return@rememberLauncherForActivityResult
        pickerBusy = true
        scope.launch {
            val codes = withContext(Dispatchers.IO) { decodeQrImage(context, uri) }
            pickerBusy = false
            when {
                codes.isEmpty() -> toast = "这张图片里没有识别到二维码，换一张试试"
                codes.size == 1 -> { if (handled.compareAndSet(false, true)) onResult(codes.first()) }
                else -> candidates = codes
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            hasPermission -> {
                CameraPreview(
                    lifecycleOwner = lifecycleOwner,
                    onCameraReady = { camera = it },
                    onDecoded = { raw -> if (handled.compareAndSet(false, true)) onResult(raw) },
                )
                ScanReticle(Modifier.align(Alignment.Center))
                Text(
                    "将二维码放入框内，即可自动扫描",
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(top = 168.dp).fillMaxWidth().padding(horizontal = 40.dp),
                )
            }
            deniedOnce -> ScanPermissionDenied(
                onOpenSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
            )
        }

        // 「从相册选择」不需要相机权限，两种权限态都露出——对齐 iOS 即便相机被拒也留着这条路。
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)
                .clickable(enabled = !pickerBusy) {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
        ) {
            Text(
                if (pickerBusy) "识别中…" else "从相册选择",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onClose) {
                Icon(Lucide.X, contentDescription = "关闭", tint = Color.White)
            }
            if (hasPermission) {
                IconButton(onClick = { torchOn = !torchOn }) {
                    Icon(
                        if (torchOn) Lucide.Flashlight else Lucide.FlashlightOff,
                        contentDescription = "手电筒",
                        tint = Color.White,
                    )
                }
            } else {
                Box(Modifier.size(48.dp)) // 占位，保持关闭按钮不因缺右侧按钮而跑偏
            }
        }
    }

    candidates?.let { list ->
        ActionSheet(
            title = "这张图里有多个二维码",
            items = list.map { code ->
                SheetItem(qrScanLabelFor(code)) {
                    if (handled.compareAndSet(false, true)) onResult(code)
                }
            },
            onDismiss = { candidates = null },
        )
    }

    toast?.let { t -> IMToast(t) { toast = null } }
}

/**
 * 从相册图片里识别二维码：读流 → 按目标边长降采样（原图可能几千像素，直接摊平成 IntArray 会 OOM）→
 * 摘像素喂给纯逻辑层。**整段包 try/catch**（对齐 `AvatarPrepare.decodeSampled` 同一处理）：
 * `openInputStream` 对 content URI 可能抛 `FileNotFoundException`/`SecurityException`
 * （临时读权限在进程被回收后失效、云端占位图未下载完成等），不接住会让协程直接崩掉整个 App
 * 而不是按设计走「没有识别到二维码」这条路（`/code-review` 抓出）。
 */
private suspend fun decodeQrImage(context: Context, uri: Uri): List<String> = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val w0 = bounds.outWidth
    val h0 = bounds.outHeight
    if (w0 <= 0 || h0 <= 0) {
        emptyList()
    } else {
        var sample = 1
        while (w0 / sample > MAX_DECODE_SIDE || h0 / sample > MAX_DECODE_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
            bitmap?.recycle()
            emptyList()
        } else {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.recycle()
            QrImageDecode.decode(w, h, pixels)
        }
    }
} catch (e: Exception) {
    qrScanLog.w("qr_album_decode_failed", "err" to e.javaClass.simpleName)
    emptyList()
}

private val qrScanLog = IMLog.tag("IM.Qr")

/**
 * 降采样目标边长——够扫码识别用，又不至于摊出一张几十 MB 的 IntArray（2000×2000×4B≈16MB，
 * 现代机型可接受）。iOS `CIDetector` 是按原始分辨率识别、不降采样；本端为避免 OOM 做了这层
 * 折中，**未在真机上用「大截图里嵌一枚很小的客服码」这类极端场景验证过是否会漏检**
 * （`/code-review` 提醒）——常见的「群码占大半屏」场景不受影响，真遇到漏检再按需调大或提示裁剪。
 */
private const val MAX_DECODE_SIDE = 2000

@Composable
private fun CameraPreview(
    lifecycleOwner: LifecycleOwner,
    onCameraReady: (Camera) -> Unit,
    onDecoded: (String) -> Unit,
) {
    val executor = remember { Executors.newSingleThreadExecutor() }
    // `bindToLifecycle` 绑的是 Activity 级生命周期（单 Activity 架构），本页从 Compose 树摘除
    // 不会自动触发解绑——不显式 unbindAll() 的话，关掉扫码页后摄像头指示灯不灭，一直占着硬件/耗电，
    // 直到下次进扫码页覆盖绑定或整个 App 切后台（`/code-review` 抓出）。
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener(
                {
                    val p = providerFuture.get()
                    val preview = Preview.Builder().build()
                        .also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor, QrFrameAnalyzer(onDecoded))
                    p.unbindAll()
                    val camera = p.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                    )
                    provider = p
                    onCameraReady(camera)
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}

/** ImageAnalysis 的逐帧分析器：只吃 Y（亮度）平面，QR 解码本就不需要色度。 */
private class QrFrameAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    override fun analyze(image: ImageProxy) {
        try {
            val text = decode(image)
            if (text != null) onDecoded(text)
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val data: ByteArray
        if (pixelStride == 1 && rowStride == width) {
            data = ByteArray(buffer.remaining())
            buffer.get(data)
        } else {
            // 部分机型 Y 平面带行距 padding，直接整段拷贝会把图拉斜——逐行按 rowStride/pixelStride 摘。
            data = ByteArray(width * height)
            val row = ByteArray(rowStride)
            for (y in 0 until height) {
                val remaining = buffer.remaining()
                if (remaining <= 0) break
                buffer.get(row, 0, minOf(rowStride, remaining))
                for (x in 0 until width) data[y * width + x] = row[x * pixelStride]
            }
        }
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decode(bitmap, hints).text
        } catch (e: NotFoundException) {
            null
        } catch (e: Exception) {
            null
        } finally {
            reader.reset()
        }
    }
}

/** 取景框：四角描边，比整屏遮罩直观——对齐 iOS 的 L 形四角。 */
@Composable
private fun ScanReticle(modifier: Modifier = Modifier) {
    val side = 220.dp
    val stroke = 3.dp
    val corner = 26.dp
    Box(modifier.size(side)) {
        val color = Color.White
        // 四角各两条短边，Compose 用四个小方块拼角比自绘 Canvas 路径简单，够用。
        Box(Modifier.align(Alignment.TopStart).size(corner, stroke).background(color))
        Box(Modifier.align(Alignment.TopStart).size(stroke, corner).background(color))
        Box(Modifier.align(Alignment.TopEnd).size(corner, stroke).background(color))
        Box(Modifier.align(Alignment.TopEnd).size(stroke, corner).background(color))
        Box(Modifier.align(Alignment.BottomStart).size(corner, stroke).background(color))
        Box(Modifier.align(Alignment.BottomStart).size(stroke, corner).background(color))
        Box(Modifier.align(Alignment.BottomEnd).size(corner, stroke).background(color))
        Box(Modifier.align(Alignment.BottomEnd).size(stroke, corner).background(color))
    }
}

@Composable
private fun ScanPermissionDenied(onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 34.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "需要相机权限",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                "开启后即可扫描二维码加好友、进群。",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
            Box(
                Modifier.padding(top = 20.dp).width(180.dp).height(44.dp)
                    .background(Color(0xFF0A85FF), RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Text("去设置开启", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
