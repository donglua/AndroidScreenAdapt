plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register("check") {
    group = "verification"
    description = "Runs all checks across autosize, consumer-test, and sample."
    dependsOn(":autosize:check", ":consumer-test:check", ":sample:check", ":sample:assembleRelease")
}
