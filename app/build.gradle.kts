/**
 * Версия релиза приходит из тега: `-PreleaseVersion=1.2.3[-alpha.N|-beta.N|-rc.N]` (§13.2 плана).
 * versionCode = MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + STAGE (alpha 1–29, beta 31–59, rc 61–89, финал 99).
 */
val releaseVersion: String? = providers.gradleProperty("releaseVersion").orNull

fun versionCodeOf(version: String): Int {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta|rc)\.(\d+))?$""").matchEntire(version)
        ?: error("Неверная версия «$version»: ожидается X.Y.Z или X.Y.Z-(alpha|beta|rc).N")
    val (major, minor, patch, stage, n) = m.destructured
    val num = n.toIntOrNull() ?: 0
    require(stage.isEmpty() || num in 1..29) { "Номер предрелиза должен быть 1..29" }
    val stageCode = when (stage) {
        "alpha" -> num
        "beta" -> 30 + num
        "rc" -> 60 + num
        else -> 99
    }
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + stageCode
}

val keystorePath: String? = System.getenv("SHIORI_KEYSTORE_PATH")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.velcheor137.shiori"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.github.velcheor137.shiori"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersion?.let(::versionCodeOf) ?: 8
        versionName = releaseVersion ?: "0.4.0-dev"
    }

    signingConfigs {
        // Ключ подписи — только из окружения (GitHub Secrets в release.yml или локально), никогда из репозитория.
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SHIORI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SHIORI_KEY_ALIAS")
                keyPassword = System.getenv("SHIORI_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":data:auth"))
    implementation(project(":data:libsocial"))
    implementation(project(":engine:typography"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.compose.icons.core)
    implementation(libs.androidx.work.runtime)
    implementation(project(":engine:ranges"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
