plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    // Отчёт с метриками нормализации (SPIKE-TYPO-001) пишется сюда.
    systemProperty("shiori.reportDir", layout.buildDirectory.dir("reports/typography").get().asFile.absolutePath)
    testLogging {
        events("failed")
        showStandardStreams = true
    }
}
