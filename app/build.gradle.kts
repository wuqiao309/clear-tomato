import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * 签名材料从 `keystore.properties` 读（该文件不进仓库）。
 *
 * 没有这个文件也能构建，release 包会退回 debug 签名 —— 能用，但和正式签名不是一套，
 * 换签名覆盖安装会失败，需要先卸载。
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.clearapp.module"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clearapp.module"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // Xposed 模块的入口与元数据按 libxposed 约定从 resources 打包进 META-INF/xposed/。
    packaging {
        resources {
            excludes += "META-INF/*.version"
        }
    }
}

dependencies {
    // 模块 API 由宿主框架提供，绝不能打进 APK。
    compileOnly(libs.libxposed.api)
}
