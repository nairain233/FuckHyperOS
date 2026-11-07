buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.compose.compiler) apply false
}

allprojects {
    // Kotlin 编译器缓存归入构建目录，不在仓库根目录生成 .kotlin。
    extra["kotlin.project.persistent.dir"] = rootProject.layout.buildDirectory
        .dir("kotlin-project-cache").get().asFile.absolutePath
}

tasks.register("Delete", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
