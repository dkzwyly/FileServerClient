// build.gradle.kts (项目级)
plugins {
    id("com.android.application") version "8.1.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
}

// 移除所有 repositories 配置，因为它们现在在 settings.gradle.kts 中

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}