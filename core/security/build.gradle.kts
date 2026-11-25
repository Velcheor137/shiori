plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.velcheor137.shiori.core.security"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        minSdk = 26
    }
}

kotlin {
    jvmToolchain(21)
}
