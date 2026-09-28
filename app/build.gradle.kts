import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// im-rtc 信令地址：只读 local.properties（已被 .gitignore 忽略），缺了就是空串，
// 此时 RtcConfig.isUsable=false、通话入口给出提示，不影响其他功能。
// 接入票改由 IMServer 的 POST /api/v1/rtc/token 代为换取，这里不再需要 appId/keyId/debugSecret。
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun rtcProp(name: String): String =
    (localProps.getProperty(name) ?: "").trim().replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.libeyond.imandroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.libeyond.imandroid"
        // minSdk 26 (Android 8.0)：覆盖率已 >95%，且免去 java.time / 通知渠道的兼容分支。
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // im-rtc 信令地址（值来自 local.properties，见上）。
        buildConfigField("String", "RTC_WS_URL", "\"${rtcProp("rtc.wsUrl")}\"")
    }

    buildTypes {
        debug {
            // 开发期后端：模拟器用 10.0.2.2 回环到宿主机的 :8080（127.0.0.1 在模拟器里指模拟器自己）。
            buildConfigField("String", "DEFAULT_HOST", "\"10.0.2.2:8080\"")
            buildConfigField("boolean", "USE_TLS", "false")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "DEFAULT_HOST", "\"\"")
            buildConfigField("boolean", "USE_TLS", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Room schema 导出：迁移测试需要，且把表结构变更纳入 code review 视野
    // （schema JSON 进版本库，加一列就能在 diff 里看见）。
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    // 源码目录用 kotlin/ 而非 java/（纯 Kotlin 工程）
    sourceSets {
        getByName("main")  { kotlin.srcDirs("src/main/kotlin") }
        getByName("test")  { kotlin.srcDirs("src/test/kotlin") }
        getByName("androidTest") { kotlin.srcDirs("src/androidTest/kotlin") }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // 自建相册选择器。依赖方向单向：app → media-picker，模块不认识 IM 业务
    implementation(project(":media-picker"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.icons.lucide)
    implementation(libs.coil.compose)
    // 本地 content:// 视频的首帧（待发气泡）——服务端回来的视频有 poster，本地那段没有
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.zxing.core)
    // 扫一扫取景：预览+分析用 CameraX，解码仍用上面的 zxing-core（PlanarYUVLuminanceSource 直接吃 Y 平面）。
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    // im-rtc 通话 SDK：Kit 接管整套通话界面，webrtc 是媒体实现。
    implementation(libs.imrtc.uikit)
    implementation(libs.imrtc.webrtc)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.prefs)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
