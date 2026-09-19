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
        // im-rtc SDK 联调期的本机包（在 im-rtc-android 里 `./gradlew publishToMavenLocal`）。
        // **只让这一个 group 走 mavenLocal**：别的依赖从本机缓存拉到，排查起来是另一场灾难。
        mavenLocal { content { includeGroup("com.github.BLiYing.im-rtc-android") } }
    }
}

rootProject.name = "im-android"
include(":app")
include(":media-picker")
