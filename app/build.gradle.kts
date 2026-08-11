plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.ai.fler"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ai.fler"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++20", "-fvisibility=hidden")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 真机冒烟用本地 debug 签名，避免依赖发布签名
            signingConfig = signingConfigs.getByName("debug")
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric 需要资源类加载（isIncludeAndroidResources）
            isIncludeAndroidResources = true
            all {
                it.useJUnit()
                // Robolectric 默认最大 SDK 支持到 35，编译 SDK 36 时固定运行在可运行 SDK
                it.systemProperty("robolectric.enabledSdks", "28,29,30,31,32,33,34,35")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.material)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp
    implementation(libs.okhttp)

    // 7z 解压
    implementation(libs.commons.compress)
    implementation(libs.xz)

    // DocumentFile (SAF)
    implementation(libs.androidx.documentfile)

    // kotlinx-serialization（MCP JSON-RPC）
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// ══════════════════════════════════════════════════════════
// Keystone 静态库（使用本地交叉编译产物，不远程下载）
// 产物：libs/arm64-v8a/libkeystone.a（scripts/build-keystone.sh 或 keystone-build
// Action 的 Release 产物本地放置）
// ══════════════════════════════════════════════════════════
val fetchKeystone = tasks.register("fetchKeystone") {
    // 配置期捕获 File（java.io.File 可序列化），执行期零 project 引用（配置缓存兼容）
    val dest = project.file("libs/arm64-v8a/libkeystone.a")
    doLast {
        if (!dest.exists() || dest.length() == 0L) {
            throw GradleException(
                "libkeystone.a 不存在: ${dest.absolutePath}\n" +
                    "请先在本地交叉编译（scripts/build-keystone.sh）并把 libkeystone-arm64-v8a.a 放到该路径。"
            )
        }
        logger.lifecycle("keystone: 使用本地编译 ${dest.absolutePath}（${dest.length()} 字节）")
    }
}

// Frida-core 静态库：frida-core-devkit-<ver>-android-arm64.tar.xz 的
// libfrida-core.a + frida-core.h（Frida 官网 release assets，随版本固定）。
// 同 fetchKeystone：文件由本地脚本 prepare（scripts/fetch-frida-devkit.*）
// 放置到工程内，本任务仅做存在性校验，配置缓存安全。
val fetchFridaDevkit = tasks.register("fetchFridaDevkit") {
    val libDest = file("libs/arm64-v8a/libfrida-core.a")
    val headerDest = file("src/main/cpp/include/frida/frida-core.h")
    doLast {
        if (!libDest.exists() || libDest.length() == 0L) {
            throw GradleException(
                "libfrida-core.a 不存在: ${libDest.absolutePath}\n" +
                    "请先运行 scripts/fetch-frida-devkit.ps1（或 .sh）下载 frida-core-devkit-<ver>-android-arm64\n" +
                    "并解压 libfrida-core.a 到 app/libs/arm64-v8a/、frida-core.h 到 app/src/main/cpp/include/frida/"
            )
        }
        if (!headerDest.exists() || headerDest.length() == 0L) {
            throw GradleException(
                "frida-core.h 不存在: ${headerDest.absolutePath}\n" +
                    "请从 frida 官方 devkit 解压该头文件到 app/src/main/cpp/include/frida/"
            )
        }
        logger.lifecycle("frida: 使用 frida-core devkit ${libDest.absolutePath}（${libDest.length()} 字节）")
    }
}

tasks.named("preBuild") {
    dependsOn(fetchKeystone)
    dependsOn(fetchFridaDevkit)
}
