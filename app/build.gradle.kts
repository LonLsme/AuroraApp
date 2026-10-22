import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/*
 * Release 签名配置。
 *
 * 仓库里**不含任何签名文件**。要打可发布的包，在项目根目录建一个 keystore.properties：
 *
 *     storeFile=/绝对路径/aurora-release.jks
 *     storePassword=******
 *     keyAlias=aurora
 *     keyPassword=******
 *
 * 该文件已在 .gitignore 里，不会被提交。文件不存在时 release 仍能编（产出未签名 APK），
 * 不会让「只想编译看看」的贡献者被卡住。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

android {
    namespace = "com.aurora.app"

    // AGP 9 语法：API 36 的 minor 版本（36.1）。需要 SDK Platform 36.1，不能只装 android-36。
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.aurora.app"
        minSdk = 24
        targetSdk = 36

        // 发版时这两项必须同时改，并与 CHANGELOG.md 的版本标题一致
        versionCode = 5
        versionName = "1.1.3"

        // 检查更新指向的仓库（owner/repo），值来自 gradle.properties。
        // 抽成 BuildConfig 而不是写死在 Java 里：Fork / 改仓库名时只改 gradle.properties 一行，
        // 代码零改动；也让 CI 能在命令行用 -Paurora.updateRepo=xxx 覆盖。
        val updateRepo = (project.findProperty("aurora.updateRepo") as String?)
            ?.trim().orEmpty()
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 刻意不开 R8：项目大量使用反射读取系统属性、以及 Compose + XML 混合布局，
            // 混淆规则没有经过完整回归。要开启请先补全 proguard-rules.pro 并在多机型验证。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.activity.compose)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.viewpager2)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.backdrop)
    implementation(libs.capsule)
    implementation(libs.kotlinx.coroutines.android)

    // Root 检测
    implementation(libs.rootbeer)
    // OTA 云提取
    implementation(libs.okhttp)
    // 刷机包解析：payload.bin / .7z / xz
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.commons.io)

    testImplementation(libs.junit)
    // 真实的 org.json：单测要覆盖 parseLatest 的资产 / 摘要解析，
    // 而 android.jar 里那份是空桩，调用会直接抛 "not mocked"
    testImplementation(libs.json)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
