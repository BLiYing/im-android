pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // im-rtc SDK 正式包（2.0.0 起走 JitPack，由 im-rtc-android 的 GitHub tag 触发构建）。
        // **只让这一个 group 走 JitPack**：别的依赖从这里拉到，排查起来是另一场灾难。
        maven("https://jitpack.io") { content { includeGroup("com.github.BLiYing.im-rtc-android") } }
        // 本地联调（用尚未发布的 SDK 本机包）：取消下面这条注释，并先在 im-rtc-android 里
        // `VERSION=<版本> ./gradlew publishToMavenLocal`；同时把 libs.versions.toml 的 imrtc 改成同一版本。
        // **只让这一个 group 走 mavenLocal**：别的依赖从本机缓存拉到，排查起来是另一场灾难。
        // 用完记得重新注释掉，否则本机残留的旧包会悄悄顶替 JitPack 的正式包。
        // mavenLocal { content { includeGroup("com.github.BLiYing.im-rtc-android") } }
    }
}

rootProject.name = "im-android"
include(":app")
include(":media-picker")
