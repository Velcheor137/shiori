plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.jsoup)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit)
}
