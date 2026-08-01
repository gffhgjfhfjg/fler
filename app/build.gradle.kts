import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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
            optimization {
                enable = false
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
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// ══════════════════════════════════════════════════════════
// Keystone 静态库获取（构建期下载，GitHub Actions 交叉编译产物）
// 产物仓库：github.com/myfler/keystone-build
// Release tag：keystone-latest-arm64-v1
// ══════════════════════════════════════════════════════════
val keystoneReleaseTag = "keystone-latest-arm64-v1"
val keystoneBaseUrl = "https://github.com/myfler/keystone-build/releases/download/$keystoneReleaseTag"

val fetchKeystone = tasks.register("fetchKeystone") {
    // 构建期网络下载任务，与配置缓存不兼容
    notCompatibleWithConfigurationCache("下载 keystone .a（构建期网络依赖）")
    doLast {
        // 执行期计算路径，避免配置缓存序列化 project File 引用
        val dest = project.file("libs/arm64-v8a/libkeystone.a")
        if (dest.exists() && dest.length() > 0) {
            logger.lifecycle("keystone: 已存在 ${dest.absolutePath}（${dest.length()} 字节），跳过下载")
            return@doLast
        }
        dest.parentFile.mkdirs()
        val url = URI("$keystoneBaseUrl/libkeystone-arm64-v8a.a").toURL()
        logger.lifecycle("keystone: 下载 $url")
        url.openStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        if (dest.length() == 0L) {
            throw GradleException("keystone 下载失败或为空: $url")
        }
        logger.lifecycle("keystone: 已下载 ${dest.length()} 字节 -> ${dest.absolutePath}")
    }
}

tasks.named("preBuild") {
    dependsOn(fetchKeystone)
}
