// 根构建脚本：只声明插件（apply false），实际应用在各模块里。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    // FCM（M5 批次 2）：只在 app 模块里、且只在 app/google-services.json 存在时才真正 apply
    // （见 app/build.gradle.kts 的判断）——这里 apply false 只是把插件版本注册进构建脚本类路径。
    alias(libs.plugins.google.services) apply false
}
