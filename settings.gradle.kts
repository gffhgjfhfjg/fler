import org.gradle.api.initialization.resolve.RepositoriesMode

// 注意：pluginManagement {} 在特殊早期阶段求值，无法引用本脚本顶层声明的 val/fun，
// 因此 CI 判断必须内联读取环境变量（GitHub Actions 会设置 CI=true）。
// CI 下走官方仓库（阿里云镜像在海外 runner 上不可靠），本地走阿里云镜像加速。
pluginManagement {
    repositories {
        if (System.getenv("CI")?.equals("true", ignoreCase = true) == true) {
            // CI（GitHub Actions）：直接走官方仓库
            google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
            mavenCentral()
            gradlePluginPortal()
        } else {
            // 本地（国内网络）：优先阿里云镜像加速
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
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
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI")?.equals("true", ignoreCase = true) == true) {
            google()
            mavenCentral()
        } else {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "fler"
include(":app")
