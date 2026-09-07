// :media-picker —— 自建相册选择器，独立成库模块。
//
// **为什么独立**：这一整块（MediaStore 查询 / 权限三版本分叉 / 宫格选择 / 压缩 / 缩放预览）
// 与 IM 业务没有任何耦合，换个 App 也能用；混在 app 模块里迟早被业务代码渗透
// （某个页面直接读 MediaAsset、某个 ViewModel 直接调 MediaStoreSource），到时就拆不动了。
//
// **依赖方向是单向的：app → media-picker，反向禁止**。所以本模块不认识 IMTheme、不认识 IMLog，
// 主题走 [MediaPickerSkin]、日志走 [MediaPickerLog] 两个接缝由调用方注入。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.libeyond.mediapicker"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    sourceSets {
        getByName("main") { kotlin.srcDirs("src/main/kotlin") }
        getByName("test") { kotlin.srcDirs("src/test/kotlin") }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.coil.compose)
    // 视频首帧缩略图（VideoFrameDecoder）；不加的话视频格子是一片空白
    implementation(libs.coil.video)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
